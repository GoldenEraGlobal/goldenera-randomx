/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2022-2030 The XdagJ Developers
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package global.goldenera.randomx;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;

/**
 * Wrapper class for RandomX virtual machine operations.
 * Manages the lifecycle and state of a RandomX VM instance.
 */
@Slf4j
public class RandomXVM implements AutoCloseable {
    /**
     * Maximum allowed input size for hash calculation (1MB).
     * This prevents potential DoS attacks via extremely large inputs.
     */
    private static final int MAX_INPUT_SIZE = 1024 * 1024;
    private static final int INITIAL_INPUT_BUFFER_SIZE = 256;

    /**
     * Thread-local buffer for input data to avoid repeated Memory allocations.
     * Reusing Memory objects significantly reduces GC pressure and native memory
     * allocation overhead
     * in high-frequency hashing scenarios (e.g., mining).
     */
    private static final ThreadLocal<Memory> INPUT_BUFFER = ThreadLocal
            .withInitial(() -> new Memory(INITIAL_INPUT_BUFFER_SIZE));

    /**
     * Thread-local buffer for output data (32 bytes for RandomX hash).
     * Reused across multiple hash calculations within the same thread.
     */
    private static final ThreadLocal<Memory> OUTPUT_BUFFER = ThreadLocal
            .withInitial(() -> new Memory(RandomXUtils.RANDOMX_HASH_SIZE));

    private static final ThreadLocal<Memory> COMMITMENT_HASH_BUFFER = ThreadLocal
            .withInitial(() -> new Memory(RandomXUtils.RANDOMX_HASH_SIZE));

    /**
     * The RandomX flags used to configure this VM.
     * Returns an unmodifiable view to prevent external modification.
     */
    private final Set<RandomXFlag> flags;

    /**
     * Gets the flags used to configure this VM.
     *
     * @return An unmodifiable set of RandomX flags.
     */
    public Set<RandomXFlag> getFlags() {
        return Collections.unmodifiableSet(flags);
    }

    /**
     * Pointer to the native VM instance.
     */
    @Getter
    private volatile Pointer vmPointer;

    /**
     * The cache used by this VM.
     */
    @Getter
    private RandomXCache cache;

    /**
     * The dataset used by this VM (may be null in light mode).
     */
    @Getter
    private RandomXDataset dataset;

    /**
     * Creates a new RandomX VM instance with the specified configuration.
     *
     * @param flags   Configuration flags for the VM.
     * @param cache   The cache to use for VM operations.
     * @param dataset The dataset to use for VM operations (may be null for light
     *                mode).
     * @throws RuntimeException         if VM creation fails.
     * @throws IllegalArgumentException if parameters are invalid.
     */
    public RandomXVM(Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset) {
        if (flags == null || flags.isEmpty()) {
            throw new IllegalArgumentException("Flags cannot be null or empty.");
        }
        if (cache == null) {
            throw new IllegalArgumentException("Cache instance cannot be null.");
        }

        this.flags = Set.copyOf(flags);
        this.cache = cache;
        this.dataset = dataset;

        int flagsValue = RandomXFlag.toValue(flags);
        Pointer cachePtr = cache.retainPointer();
        Pointer datasetPtr = null;
        try {
            datasetPtr = dataset == null ? null : dataset.retainPointer();
        } catch (RuntimeException | Error failure) {
            cache.releasePointer();
            throw failure;
        }

        log.debug("Preparing to create RandomX VM. Flags: {} ({}), Cache Ptr: {}, Dataset Ptr: {}",
                flags, flagsValue, Pointer.nativeValue(cachePtr),
                (datasetPtr != null ? Pointer.nativeValue(datasetPtr) : "null"));

        try {
            this.vmPointer = RandomXNative.randomx_create_vm(flagsValue, cachePtr, datasetPtr);
        } catch (RuntimeException | Error failure) {
            if (dataset != null) {
                dataset.releasePointer();
            }
            cache.releasePointer();
            throw failure;
        }

        if (vmPointer == null) {
            if (dataset != null) {
                dataset.releasePointer();
            }
            cache.releasePointer();
            String errorMsg = String.format("Failed to create RandomX VM with flags: %s (%d)", flags, flagsValue);
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        log.debug("RandomX VM created successfully. Pointer: {}, Flags: {}", Pointer.nativeValue(vmPointer), flags);
    }

    /**
     * Updates the cache used by this VM.
     *
     * @param newCache The new cache to use.
     * @throws IllegalArgumentException if newCache is null or its pointer is null.
     * @throws IllegalStateException    if the VM pointer is null.
     */
    public synchronized void setCache(RandomXCache newCache) {
        Pointer pointer = vmPointer;
        if (pointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot set cache.");
        }
        if (flags.contains(RandomXFlag.FULL_MEM)) {
            throw new IllegalStateException("Cache replacement is only valid for LIGHT RandomX VMs.");
        }
        if (newCache == null) {
            throw new IllegalArgumentException("New cache instance cannot be null.");
        }
        Pointer newCachePointer = newCache.retainPointer();
        RandomXCache previousCache = cache;
        try {
            RandomXNative.randomx_vm_set_cache(pointer, newCachePointer);
        } catch (RuntimeException | Error failure) {
            newCache.releasePointer();
            throw failure;
        }
        this.cache = newCache;
        previousCache.releasePointer();
        log.debug("VM cache updated. New Cache Ptr: {}", Pointer.nativeValue(newCachePointer));
    }

    /**
     * Updates the dataset used by this VM.
     *
     * @param newDataset The replacement dataset for a FULL RandomX VM.
     * @throws IllegalArgumentException if newDataset or its pointer is null.
     * @throws IllegalStateException    if the VM pointer is null.
     */
    public synchronized void setDataset(RandomXDataset newDataset) {
        Pointer pointer = vmPointer;
        if (pointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot set dataset.");
        }
        if (!flags.contains(RandomXFlag.FULL_MEM)) {
            throw new IllegalStateException("Dataset replacement is only valid for FULL RandomX VMs.");
        }
        if (newDataset == null) {
            throw new IllegalArgumentException("Replacement dataset cannot be null.");
        }
        Pointer datasetPtr = newDataset.retainPointer();
        RandomXDataset previousDataset = dataset;
        try {
            RandomXNative.randomx_vm_set_dataset(pointer, datasetPtr);
        } catch (RuntimeException | Error failure) {
            newDataset.releasePointer();
            throw failure;
        }
        this.dataset = newDataset;
        if (previousDataset != null) {
            previousDataset.releasePointer();
        }
        log.debug("VM dataset updated. New Dataset Ptr: {}",
                (datasetPtr != null ? Pointer.nativeValue(datasetPtr) : "null"));
    }

    /**
     * Calculates a RandomX hash using the current VM configuration.
     *
     * @param input The input data to be hashed.
     * @return A 32-byte array containing the calculated hash.
     * @throws IllegalArgumentException if input is null or exceeds maximum size.
     * @throws IllegalStateException    if the VM pointer is null.
     */
    public synchronized byte[] calculateHash(byte[] input) {
        byte[] output = new byte[RandomXUtils.RANDOMX_HASH_SIZE];
        calculateHashInto(input, output, 0);
        return output;
    }

    /**
     * Calculates a RandomX hash into a caller-owned output array.
     *
     * @param input        input data to hash
     * @param output       destination array
     * @param outputOffset first destination byte; 32 bytes are written
     */
    public synchronized void calculateHashInto(byte[] input, byte[] output, int outputOffset) {
        Pointer pointer = vmPointer;
        if (pointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot calculate hash.");
        }
        validateInput(input);
        validateOutput(output, outputOffset);

        Memory inputMem = inputBuffer(input.length);
        Memory outputMem = OUTPUT_BUFFER.get();

        if (input.length > 0) {
            inputMem.write(0, input, 0, input.length);
        }
        RandomXNative.randomx_calculate_hash(pointer, inputMem, input.length, outputMem);
        outputMem.read(0, output, outputOffset, RandomXUtils.RANDOMX_HASH_SIZE);
    }

    /**
     * Starts the RandomX pipeline for the first independent input.
     *
     * @param input The first complete input, not a streaming fragment.
     * @throws IllegalArgumentException if input is null.
     * @throws IllegalStateException    if the VM pointer is null.
     */
    public synchronized void calculateHashFirst(byte[] input) {
        Pointer pointer = vmPointer;
        if (pointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot start multi-part hash.");
        }
        validateInput(input);

        // Reuse thread-local buffer
        Memory inputMem = inputBuffer(input.length);
        if (input.length > 0) {
            inputMem.write(0, input, 0, input.length);
        }
        RandomXNative.randomx_calculate_hash_first(pointer, inputMem, input.length);
    }

    /**
     * Returns the previous pipeline hash while starting the next independent input.
     *
     * @param input The next complete input, not a streaming fragment.
     * @return The 32-byte hash of the input supplied to the preceding pipeline call.
     * @throws IllegalArgumentException if input is null.
     * @throws IllegalStateException    if the VM pointer is null.
     */
    public synchronized byte[] calculateHashNext(byte[] input) {
        Pointer pointer = vmPointer;
        if (pointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot continue multi-part hash.");
        }
        validateInput(input);

        byte[] output = new byte[RandomXUtils.RANDOMX_HASH_SIZE];
        Memory inputMem = inputBuffer(input.length);
        Memory outputMem = OUTPUT_BUFFER.get();

        if (input.length > 0) {
            inputMem.write(0, input, 0, input.length);
        }
        RandomXNative.randomx_calculate_hash_next(pointer, inputMem, input.length, outputMem);
        outputMem.read(0, output, 0, output.length);
        return output;
    }

    /**
     * Returns the hash of the final input currently pending in the pipeline.
     *
     * @return A 32-byte array containing the final hash result.
     * @throws IllegalStateException if the VM pointer is null.
     */
    public synchronized byte[] calculateHashLast() {
        Pointer pointer = vmPointer;
        if (pointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot finalize multi-part hash.");
        }

        byte[] output = new byte[RandomXUtils.RANDOMX_HASH_SIZE];
        Memory outputMem = OUTPUT_BUFFER.get();

        RandomXNative.randomx_calculate_hash_last(pointer, outputMem);
        outputMem.read(0, output, 0, output.length);

        return output;
    }

    /**
     * Calculates a commitment hash for the given input data.
     * Note: The implementation of this method is based on observation of the
     * original code.
     * It first calculates a regular hash, then uses that hash as a seed to
     * calculate the commitment.
     * The exact behavior and signature of the {@code randomx_calculate_commitment}
     * C API should be confirmed.
     *
     * @param originalInput     The original input data that was hashed.
     * @param preCalculatedHash The hash previously calculated from originalInput.
     * @return A 32-byte array containing the calculated commitment.
     * @throws IllegalArgumentException if originalInput or preCalculatedHash is
     *                                  null, or if preCalculatedHash is not 32
     *                                  bytes.
     * @throws IllegalStateException    if the VM pointer is null.
     */
    public synchronized byte[] calculateCommitment(byte[] originalInput, byte[] preCalculatedHash) {
        if (vmPointer == null) {
            throw new IllegalStateException("VM pointer is null, cannot calculate commitment.");
        }
        validateInput(originalInput);
        if (preCalculatedHash == null || preCalculatedHash.length != RandomXUtils.RANDOMX_HASH_SIZE) {
            throw new IllegalArgumentException("Pre-calculated hash cannot be null and must be "
                    + RandomXUtils.RANDOMX_HASH_SIZE + " bytes long.");
        }

        byte[] commitmentOutput = new byte[RandomXUtils.RANDOMX_HASH_SIZE];

        // Reuse thread-local buffers where possible
        Memory originalInputMem = inputBuffer(originalInput.length);
        Memory outputMem = OUTPUT_BUFFER.get();

        // For preCalculatedHash, we need a separate fixed-size Memory object
        // since it's always 32 bytes and we can't reuse INPUT_BUFFER
        Memory preCalculatedHashMem = COMMITMENT_HASH_BUFFER.get();

        if (originalInput.length > 0) {
            originalInputMem.write(0, originalInput, 0, originalInput.length);
        }
        preCalculatedHashMem.write(0, preCalculatedHash, 0, preCalculatedHash.length);

        // Call the native method with parameters matching the C API
        // (Pointer input, long inputSize, Pointer hash_in, Pointer com_out)
        RandomXNative.randomx_calculate_commitment(originalInputMem, originalInput.length, preCalculatedHashMem,
                outputMem);
        outputMem.read(0, commitmentOutput, 0, commitmentOutput.length);
        return commitmentOutput;
    }

    private static void validateInput(byte[] input) {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null.");
        }
        if (input.length > MAX_INPUT_SIZE) {
            throw new IllegalArgumentException("Input size (" + input.length + " bytes) exceeds maximum allowed size: "
                    + MAX_INPUT_SIZE + " bytes");
        }
    }

    private static void validateOutput(byte[] output, int outputOffset) {
        if (output == null) {
            throw new IllegalArgumentException("Output cannot be null.");
        }
        if (outputOffset < 0 || outputOffset > output.length - RandomXUtils.RANDOMX_HASH_SIZE) {
            throw new IllegalArgumentException("Output must have 32 writable bytes at the requested offset.");
        }
    }

    private static Memory inputBuffer(int requiredSize) {
        Memory current = INPUT_BUFFER.get();
        int requiredCapacity = Math.max(1, requiredSize);
        if (current.size() >= requiredCapacity) {
            return current;
        }

        int capacity = Integer.highestOneBit(requiredCapacity - 1) << 1;
        if (capacity <= 0 || capacity > MAX_INPUT_SIZE) {
            capacity = MAX_INPUT_SIZE;
        }
        Memory replacement = new Memory(capacity);
        INPUT_BUFFER.set(replacement);
        current.close();
        return replacement;
    }

    /**
     * Releases native VM resources.
     * This method is idempotent and can be called multiple times safely.
     */
    @Override
    public synchronized void close() {
        Pointer pointer = vmPointer;
        vmPointer = null;
        if (pointer != null) {
            // Check if vmPointer is still valid to prevent operations on an already
            // destroyed VM.
            // While JNA's destroy is generally safe, this is an extra layer of protection.
            try {
                RandomXNative.randomx_destroy_vm(pointer);
                log.debug("RandomX VM destroyed. Pointer: {}", Pointer.nativeValue(pointer));
            } catch (Throwable t) {
                // Generally, destroy should not throw an error, but just in case.
                log.error("Error occurred while destroying RandomX VM. Pointer: {}", Pointer.nativeValue(pointer), t);
            } finally {
                if (dataset != null) {
                    dataset.releasePointer();
                    dataset = null;
                }
                cache.releasePointer();
                cache = null;
            }
        } else {
            log.debug(
                    "Attempting to destroy RandomX VM, but pointer is already null (possibly already destroyed or never created).");
        }
    }
}
