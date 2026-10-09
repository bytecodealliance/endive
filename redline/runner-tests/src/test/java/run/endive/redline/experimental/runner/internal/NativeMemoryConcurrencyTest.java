package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.wasm.types.MemoryLimits;

/** A shared memory is grown and read by several host threads at once. */
public class NativeMemoryConcurrencyTest {

    private static final int PAGE = 65536;

    @Test
    public void concurrentGrowsHandOutEveryPageOnce() throws Exception {
        int threads = 8;
        int growsPerThread = 200;
        var memory =
                NativeMachineFactory.createMemory(
                        new MemoryLimits(0, threads * growsPerThread, true));
        Set<Integer> handedOut = ConcurrentHashMap.newKeySet();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    for (int i = 0; i < growsPerThread; i++) {
                                        assertTrue(handedOut.add(memory.grow(1)));
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * growsPerThread, memory.pages());
        assertEquals(threads * growsPerThread, handedOut.size());
    }

    // The reader polls with plain reads, the pattern that let the JIT keep a stale view
    @Test
    public void aHostReaderCanReachPagesAnotherThreadGrew() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (int round = 0; round < 20; round++) {
                var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2, true));
                Future<Integer> reader =
                        pool.submit(
                                () -> {
                                    long deadline = System.nanoTime() + 10_000_000_000L;
                                    while (memory.readInt(0) != 1) {
                                        if (System.nanoTime() > deadline) {
                                            throw new AssertionError("flag never seen");
                                        }
                                    }
                                    return memory.readInt(PAGE + 16);
                                });
                Thread.sleep(5);
                memory.grow(1);
                memory.atomicWriteInt(0, 1);
                reader.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
