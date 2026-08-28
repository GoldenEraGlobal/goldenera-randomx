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

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Template class for RandomX operations, providing a common workflow.
 * This class encapsulates the functionality for RandomX mining and hashing
 * operations.
 */
@Builder
@ToString
@Slf4j
public class RandomXTemplate implements AutoCloseable {
    /**
     * Private constructor to be used by the Lombok generated builder.
     * Direct instantiation is discouraged; use the builder pattern.
     */
    private RandomXTemplate(boolean miningMode, Set<RandomXFlag> flags, RandomXCache cache, RandomXDataset dataset,
            RandomXVM vm, byte[] currentKey) {
        this.miningMode = miningMode;
        this.flags = Set.copyOf(flags);
        this.cache = cache;
        this.dataset = dataset;
        this.vm = vm;
        // Defensive copy to prevent external modification
        this.currentKey = currentKey != null ? Arrays.copyOf(currentKey, currentKey.length) : null;
    }

    /** Flag indicating if the template is in mining mode */
    @Getter
    private final boolean miningMode;

    /** Set of RandomX flags for configuring the algorithm behavior */
    private final Set<RandomXFlag> flags;

    /**
     * Gets the flags used to configure this RandomX template.
     *
     * @return An unmodifiable set of RandomX flags.
     */
    public Set<RandomXFlag> getFlags() {
        return Collections.unmodifiableSet(flags);
    }

    /** Cache for RandomX operations */
    @Getter
    private final RandomXCache cache;

    /** Dataset for RandomX mining operations */
    @Getter
    private RandomXDataset dataset;

    /** Virtual machine instance for RandomX operations */
    @Getter
    private RandomXVM vm;

    /**
     * Stores the current key used for cache initialization to avoid redundant
     * re-initializations.
     */
    private byte[] currentKey;

    /**
     * Gets a copy of the current key to prevent external modification of internal
     * state.
     * 
     * @return A copy of the current key, or null if no key is set.
     */
    public byte[] getCurrentKey() {
        return currentKey != null ? Arrays.copyOf(currentKey, currentKey.length) : null;
    }

    /**
     * Initializes the RandomX virtual machine (VM) with the configured settings.
     * This method must be called before any hash calculation.
     * If in mining mode, the dataset should be initialized before calling this
     * method,
     * and if in light mode, the cache should be initialized.
     */
    public synchronized void init() {
        if (vm != null) {
            throw new IllegalStateException("RandomXTemplate is already initialized.");
        }
        Set<RandomXFlag> vmFlags = EnumSet.copyOf(flags);
        RandomXDataset newDataset = null;
        if (miningMode) {
            vmFlags.add(RandomXFlag.FULL_MEM);
            // Ensure cache is initialized with currentKey before creating dataset
            if (this.currentKey == null) {
                log.warn(
                        "Initializing RandomXTemplate without a key set for the cache. Dataset initialization might rely on an uninitialized cache if not subsequently set.");
            } else if (cache.getCachePointer() == null) { // Cache might be allocated but not initialized
                log.warn(
                        "Cache pointer is null during init despite currentKey being set. This should not happen if cache is managed correctly.");
            }

            log.debug("Mining mode enabled. Creating and initializing dataset with flags: {}", vmFlags);
            newDataset = new RandomXDataset(vmFlags);
            try {
                newDataset.init(cache);
            } catch (RuntimeException | Error failure) {
                newDataset.close();
                throw failure;
            }
        } else {
            vmFlags.remove(RandomXFlag.FULL_MEM);
            log.debug("Light mode enabled. Dataset will not be used.");
        }

        log.debug("Creating RandomXVM with flags: {} (Cache: {}, Dataset: {})",
                vmFlags,
                cache != null ? "Present" : "Null",
                newDataset != null ? "Present" : "Null");
        try {
            vm = new RandomXVM(vmFlags, cache, newDataset);
            dataset = newDataset;
        } catch (RuntimeException | Error failure) {
            if (newDataset != null) {
                newDataset.close();
            }
            throw failure;
        }
        log.info("RandomXTemplate initialized. VM created.");
    }

    /**
     * Changes the current RandomX key by reinitializing the cache and, if in mining
     * mode, the dataset.
     * If the provided key is the same as the current key, this method returns
     * without reinitialization.
     *
     * @param key The new key (typically a seed hash) to initialize RandomX
     *            components with.
     * @throws IllegalArgumentException if the key is null or empty.
     */
    public synchronized void changeKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Key cannot be null or empty for changeKey operation.");
        }

        // Check if the new key is the same as the current key.
        if (Arrays.equals(this.currentKey, key)) {
            log.debug("Key is unchanged. Skipping reinitialization.");
            return;
        }

        log.info("Changing RandomX key. Old key hash (if any): {}, New key hash: {}",
                (this.currentKey != null ? Arrays.hashCode(this.currentKey) : "N/A"), Arrays.hashCode(key));

        if (miningMode && vm != null) {
            changeFullKey(key);
            return;
        }

        cache.init(key);
        if (vm != null) {
            vm.setCache(cache);
        }
        this.currentKey = Arrays.copyOf(key, key.length);
        log.info("RandomX key changed and components reinitialized successfully.");
    }

    private void changeFullKey(byte[] key) {
        if (currentKey == null) {
            throw new IllegalStateException(
                    "FULL key replacement requires the current key to have been set through changeKey before init.");
        }

        byte[] previousKey = Arrays.copyOf(currentKey, currentKey.length);
        RandomXDataset previousDataset = dataset;
        RandomXDataset replacementDataset = null;
        try {
            cache.init(key);
            Set<RandomXFlag> datasetFlags = EnumSet.copyOf(flags);
            datasetFlags.add(RandomXFlag.FULL_MEM);
            replacementDataset = new RandomXDataset(datasetFlags);
            replacementDataset.init(cache);

            vm.setDataset(replacementDataset);
            dataset = replacementDataset;
            currentKey = Arrays.copyOf(key, key.length);
            if (previousDataset != null) {
                previousDataset.close();
            }
            log.info("FULL RandomX key and dataset changed successfully.");
        } catch (RuntimeException | Error failure) {
            if (replacementDataset != null) {
                replacementDataset.close();
            }
            try {
                cache.init(previousKey);
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    /**
     * Performs a single hash calculation using the RandomX VM.
     *
     * @param input Input data for the hash calculation.
     * @return A 32-byte array containing the calculated hash.
     * @throws IllegalStateException if the VM is not initialized.
     */
    public synchronized byte[] calculateHash(byte[] input) {
        if (vm == null) {
            throw new IllegalStateException("RandomX VM is not initialized. Call init() first or ensure key is set.");
        }
        return vm.calculateHash(input);
    }

    /**
     * Starts the RandomX pipeline for the first independent input.
     * 
     * @param input First complete input, not a streaming fragment.
     * @throws IllegalStateException if the VM is not initialized.
     */
    public synchronized void calculateHashFirst(byte[] input) {
        if (vm == null) {
            throw new IllegalStateException("RandomX VM is not initialized. Call init() first or ensure key is set.");
        }
        vm.calculateHashFirst(input);
    }

    /**
     * Returns the previous pipeline hash while starting the next independent input.
     * 
     * @param nextInput Next complete input, not a streaming fragment.
     * @return The hash of the input supplied to the preceding pipeline call.
     * @throws IllegalStateException if the VM is not initialized.
     */
    public synchronized byte[] calculateHashNext(byte[] nextInput) {
        if (vm == null) {
            throw new IllegalStateException("RandomX VM is not initialized. Call init() first or ensure key is set.");
        }
        return vm.calculateHashNext(nextInput);
    }

    /**
     * Returns the hash of the final input currently pending in the pipeline.
     * 
     * @return A 32-byte array containing the final hash result.
     * @throws IllegalStateException if the VM is not initialized.
     */
    public synchronized byte[] calculateHashLast() {
        if (vm == null) {
            throw new IllegalStateException("RandomX VM is not initialized. Call init() first or ensure key is set.");
        }
        return vm.calculateHashLast();
    }

    /**
     * Calculates a commitment hash for the given input data.
     *
     * @param input The input byte array to calculate commitment for.
     * @return A byte array containing the calculated commitment hash.
     * @throws IllegalStateException if the VM is not initialized.
     */
    public synchronized byte[] calculateCommitment(byte[] input) {
        if (vm == null) {
            throw new IllegalStateException("RandomX VM is not initialized. Call init() first or ensure key is set.");
        }
        byte[] hashOfInput = vm.calculateHash(input);

        // Then, use the original input and this calculated hash to get the commitment.
        return vm.calculateCommitment(input, hashOfInput);
    }

    /**
     * Releases all allocated resources (VM and Dataset).
     * The Cache is managed externally if passed to the builder, or internally if
     * created by this template.
     * The Current implementation assumes cache is provided via builder and its
     * lifecycle is managed outside this close().
     * If RandomXTemplate were to create its own RandomXCache, it should also close
     * it here.
     *
     * Note: This method attempts to close all resources independently, ensuring
     * that failure
     * to close one resource does not prevent cleanup of others.
     */
    @Override
    public synchronized void close() {
        log.debug("Closing RandomXTemplate resources...");

        // Close VM first (highest level resource)
        if (vm != null) {
            try {
                log.debug("Closing RandomX VM...");
                vm.close();
            } catch (Exception e) {
                log.error("Failed to close RandomX VM", e);
            }
            vm = null;
        }

        // Close dataset second
        if (dataset != null) {
            try {
                log.debug("Closing RandomX Dataset...");
                dataset.close();
            } catch (Exception e) {
                log.error("Failed to close RandomX Dataset", e);
            }
            dataset = null;
        }

        // currentKey does not need explicit closing.
        // Cache is not closed here as it's assumed to be managed externally (passed in
        // via builder).
        log.info("RandomXTemplate resources closed.");
    }
}
