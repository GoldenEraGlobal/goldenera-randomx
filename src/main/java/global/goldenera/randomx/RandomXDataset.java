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

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

import java.util.Collections;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Encapsulates the RandomX dataset functionality with multi-threaded
 * initialization support.
 * Manages the allocation, initialization, and release of RandomX datasets, with
 * support for multi-threaded initialization.
 * This class implements AutoCloseable for resource management.
 * Implement the AutoCloseable interface for resource management.
 */
@Slf4j
public class RandomXDataset implements AutoCloseable {

    private static final int MAX_INITIALIZATION_THREADS = 32;

    /**
     * Pointer to the allocated RandomX dataset memory.
     */
    private volatile Pointer datasetPointer;
    private int activeUsers;

    private final Set<RandomXFlag> flags; // Store flags used for allocation

    /**
     * Gets the flags used to configure this dataset.
     *
     * @return An unmodifiable set of RandomX flags.
     */
    public Set<RandomXFlag> getFlags() {
        return Collections.unmodifiableSet(flags);
    }

    /**
     * Constructs a new RandomXDataset and allocates memory for it.
     *
     * @param flags Set of RandomXFlag values used to configure the dataset
     *              behavior.
     * @throws RuntimeException if dataset allocation fails.
     */
    public RandomXDataset(Set<RandomXFlag> flags) {
        if (flags == null || flags.isEmpty()) {
            throw new IllegalArgumentException("Flags cannot be null or empty for dataset allocation.");
        }
        this.flags = Set.copyOf(flags);
        int combinedFlags = RandomXFlag.toValue(flags);
        log.debug("Allocating RandomX dataset with flags: {} ({})", flags, combinedFlags);

        // Use RandomXNative for allocation
        this.datasetPointer = RandomXNative.randomx_alloc_dataset(combinedFlags);

        if (datasetPointer == null) {
            String errorMsg = String.format("Failed to allocate RandomX dataset with flags: %s (%d)", flags,
                    combinedFlags);
            log.error(errorMsg);
            throw new RuntimeException(errorMsg); // Use RuntimeException
        }

        log.debug("RandomX dataset allocated successfully at pointer: {} with flags: {}",
                Pointer.nativeValue(datasetPointer), flags);
    }

    /**
     * Get the optimal thread count for dataset initialization.
     * Can be overridden via system property: randomx.dataset.threads
     * Default is half of available processors.
     *
     * @return The number of threads to use for initialization.
     */
    private int getOptimalThreadCount() {
        int availableProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int maximum = Math.min(availableProcessors, MAX_INITIALIZATION_THREADS);
        String threadsProp = System.getProperty("randomx.dataset.threads");
        if (threadsProp != null && !threadsProp.isEmpty()) {
            try {
                int threads = Integer.parseInt(threadsProp);
                if (threads > 0) {
                    int boundedThreads = Math.min(threads, maximum);
                    if (boundedThreads != threads) {
                        log.warn("Capping randomx.dataset.threads from {} to {}", threads, boundedThreads);
                    }
                    return boundedThreads;
                } else {
                    log.warn("Invalid randomx.dataset.threads value (must be positive): {}, using default",
                            threadsProp);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid randomx.dataset.threads value (not a number): {}, using default", threadsProp);
            }
        }
        return Math.min(maximum, Math.max(1, availableProcessors / 2));
    }

    /**
     * Initializes the dataset using multiple threads.
     * The initialization work is divided among threads based on available CPU
     * cores.
     * Thread count can be configured via system property: randomx.dataset.threads
     *
     * @param cache The RandomXCache instance required for dataset initialization.
     * @throws RuntimeException      if initialization is interrupted or fails.
     * @throws IllegalStateException if the dataset is not allocated.
     */
    public synchronized void init(RandomXCache cache) {
        if (datasetPointer == null) {
            throw new IllegalStateException("Dataset is not allocated.");
        }
        if (cache == null || cache.getCachePointer() == null) {
            throw new IllegalArgumentException(
                    "Valid cache instance with allocated cache pointer is required for dataset initialization.");
        }

        long startTime = System.nanoTime();

        // Get total items count using RandomXNative
        long totalItems = RandomXNative.randomx_dataset_item_count().longValue();
        if (totalItems <= 0) {
            log.warn("RandomX dataset item count is zero or negative ({}). Skipping initialization.", totalItems);
            return; // No items to initialize
        }

        // Calculate optimal thread count (using half of available processors by
        // default)
        int initThreadCount = getOptimalThreadCount();
        log.info("Initializing dataset ({} items) using {} threads.", totalItems, initThreadCount);

        // Create thread pool with custom thread factory for naming
        ExecutorService executor = Executors.newFixedThreadPool(initThreadCount, new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("RandomX-Dataset-Init-" + threadNumber.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });

        List<Future<?>> futures = new ArrayList<>(initThreadCount);
        Throwable submissionFailure = null;
        try {
            // Calculate items per thread and handle remainder
            long itemsPerThread = totalItems / initThreadCount;
            long remainder = totalItems % initThreadCount;
            long currentItemStart = 0;

            // Submit initialization tasks for each thread
            for (int i = 0; i < initThreadCount; i++) {
                long itemCount = itemsPerThread + (i < remainder ? 1 : 0); // Distribute remainder evenly
                if (itemCount == 0)
                    continue; // Skip threads with no work

                final long start = currentItemStart;
                final long count = itemCount;
                final Pointer cachePtr = cache.getCachePointer(); // Pass cache pointer explicitly

                futures.add(executor.submit(() -> {
                    String threadName = Thread.currentThread().getName();
                    try {
                        log.debug("{} starting initialization for items [{}, {})", threadName, start, start + count);
                        // Use RandomXNative for dataset initialization
                        RandomXNative.randomx_init_dataset(
                                datasetPointer,
                                cachePtr,
                                new NativeLong(start),
                                new NativeLong(count));
                        log.debug("{} finished initialization for items [{}, {})", threadName, start, start + count);
                    } catch (RuntimeException | Error e) {
                        log.error("{} failed during initialization for items [{}, {}). Error: {}",
                                threadName, start, start + count, e.getMessage(), e);
                        // Propagate exception to be caught by future.get()
                        throw e;
                    }
                }));
                currentItemStart += itemCount;
            }
        } catch (RuntimeException | Error failure) {
            submissionFailure = failure;
        } finally {
            executor.shutdown();
        }

        // Never return while a native initialization call can still be writing to
        // this dataset. Native RandomX calls are not interruptible, so cancellation
        // or a timed shutdown would permit a caller to free live native memory.
        awaitAllWorkers(futures, submissionFailure);

        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        log.info("Dataset initialization completed successfully in {} ms.", durationMillis);
    }

    static void awaitAllWorkers(List<? extends Future<?>> futures, Throwable initialFailure) {
        Throwable failure = initialFailure;
        boolean interrupted = false;

        for (Future<?> future : futures) {
            boolean completed = false;
            while (!completed) {
                try {
                    future.get();
                    completed = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                } catch (ExecutionException e) {
                    Throwable workerFailure = e.getCause() == null ? e : e.getCause();
                    if (failure == null) {
                        failure = workerFailure;
                    } else if (failure != workerFailure) {
                        failure.addSuppressed(workerFailure);
                    }
                    completed = true;
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            log.error("Dataset initialization failed after all native workers stopped.", failure);
            throw new RuntimeException("Dataset initialization failed", failure);
        }
    }

    /**
     * Gets the pointer to the allocated dataset memory.
     *
     * @return Pointer to the dataset memory.
     * @throws IllegalStateException if the dataset is not allocated.
     */
    public Pointer getDatasetPointer() {
        Pointer pointer = datasetPointer;
        if (pointer == null) {
            throw new IllegalStateException("Dataset is not allocated.");
        }
        return pointer;
    }

    synchronized Pointer retainPointer() {
        Pointer pointer = getDatasetPointer();
        activeUsers++;
        return pointer;
    }

    synchronized void releasePointer() {
        if (activeUsers <= 0) {
            throw new IllegalStateException("RandomX dataset user count underflow.");
        }
        activeUsers--;
    }

    /**
     * Releases the allocated dataset memory.
     * This method is called automatically when using try-with-resources.
     */
    @Override
    public synchronized void close() {
        if (activeUsers != 0) {
            throw new IllegalStateException("RandomX dataset is still used by " + activeUsers + " VM(s).");
        }
        Pointer pointer = datasetPointer;
        datasetPointer = null;
        if (pointer != null) {
            log.debug("Releasing RandomX dataset at pointer: {}", Pointer.nativeValue(pointer));
            try {
                // Use RandomXNative for release
                RandomXNative.randomx_release_dataset(pointer);
                log.info("RandomX dataset released successfully.");
            } catch (Throwable t) {
                log.error("Error occurred while releasing RandomX dataset. Pointer: {}",
                        Pointer.nativeValue(pointer), t);
            }
        }
    }
}
