package run.endive.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.MemoryLimits;

public class MemoryTest {

    private static Stream<Arguments> memoryImplementations() {
        return Stream.of(
                Arguments.of(
                        "ByteArrayMemory",
                        (Supplier<Memory>) () -> new ByteArrayMemory(new MemoryLimits(2, 2))),
                Arguments.of(
                        "ByteBufferMemory",
                        (Supplier<Memory>) () -> new ByteBufferMemory(new MemoryLimits(2, 2))));
    }

    private static Stream<Arguments> growableMemoryImplementations() {
        return Stream.of(
                Arguments.of(
                        "ByteArrayMemory",
                        (Supplier<Memory>)
                                () -> new ByteArrayMemory(new MemoryLimits(1, 1000, true))),
                Arguments.of(
                        "ByteBufferMemory",
                        (Supplier<Memory>)
                                () -> new ByteBufferMemory(new MemoryLimits(1, 1000, true))));
    }

    /**
     * Test that concurrent grow and write operations don't lose writes.
     *
     * <p>This test has one thread incrementing a counter sequentially (read i, write i+1), while
     * another thread grows memory. If a write is lost due to a racy grow, the counter will have an
     * inconsistent value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("growableMemoryImplementations")
    public void concurrentGrowAndAccessStressTest(String name, Supplier<Memory> memorySupplier)
            throws Exception {
        final int counterAddr = 0;
        final int growIterations = 200;

        var memory = memorySupplier.get();
        memory.writeI32(counterAddr, 0);

        // Grower thread: grows memory a fixed number of times
        var grower =
                CompletableFuture.runAsync(
                        () -> {
                            for (int i = 0; i < growIterations; i++) {
                                if (memory.grow(1) < 0) {
                                    throw new AssertionError("failed to grow memory");
                                }
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        });

        // Counter thread: sequentially increment counter until grower is done
        var counter =
                CompletableFuture.runAsync(
                        () -> {
                            int i = 0;
                            while (!grower.isDone()) {
                                int read = (int) memory.readI32(counterAddr);
                                if (read != i) {
                                    throw new AssertionError(
                                            "inconsistent count at i=" + i + ", read=" + read);
                                }
                                i++;
                                memory.writeI32(counterAddr, i);
                            }
                        });

        CompletableFuture.allOf(counter, grower).get(5, TimeUnit.SECONDS);
    }

    /**
     * Test that overlapping copy with dest > src preserves data correctly.
     * This is the memmove semantics required by Wasm memory.copy.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    public void overlappingCopyDestAfterSrc(String name, Supplier<Memory> memorySupplier) {
        var memory = memorySupplier.get();

        // Write known pattern [1, 2, 3, 4, 5, 6, 7, 8] at addr 100
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        memory.write(100, data, 0, data.length);

        // Overlapping copy: src=100, dest=104, size=8
        // Expected: addr 104..111 = [1, 2, 3, 4, 5, 6, 7, 8]
        memory.copy(104, 100, 8);

        assertArrayEquals(data, memory.readBytes(104, 8));
    }

    /**
     * Test overlapping copy with dest < src (forward copy path).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    public void overlappingCopyDestBeforeSrc(String name, Supplier<Memory> memorySupplier) {
        var memory = memorySupplier.get();

        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        memory.write(104, data, 0, data.length);

        // Overlapping copy: src=104, dest=100, size=8
        memory.copy(100, 104, 8);

        assertArrayEquals(data, memory.readBytes(100, 8));
    }

    /**
     * Test overlapping copy across page boundaries (PAGE_SIZE = 65536).
     * This exercises the chunked backward copy logic when overlap spans pages.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    public void overlappingCopyCrossPageBoundary(String name, Supplier<Memory> memorySupplier) {
        var memory = memorySupplier.get();

        int pageSize = 65536;
        // Place data straddling the page boundary
        int src = pageSize - 4;
        byte[] data = {10, 20, 30, 40, 50, 60, 70, 80};
        memory.write(src, data, 0, data.length);

        // Overlapping copy with dest > src, crossing page boundary
        int dest = src + 4;
        memory.copy(dest, src, data.length);

        assertArrayEquals(data, memory.readBytes(dest, data.length));
    }

    /**
     * Test non-overlapping copy across pages.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    public void nonOverlappingCopyCrossPage(String name, Supplier<Memory> memorySupplier) {
        var memory = memorySupplier.get();

        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        memory.write(100, data, 0, data.length);

        // Copy to a completely different location on a different page
        int dest = 65536 + 200;
        memory.copy(dest, 100, data.length);

        assertArrayEquals(data, memory.readBytes(dest, data.length));
        // Source should be unchanged
        assertArrayEquals(data, memory.readBytes(100, data.length));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    public void checkBoundsOverflowShouldTrap(String name, Supplier<Memory> memorySupplier) {
        var memory = memorySupplier.get();
        assertThrows(WasmEngineException.class, () -> memory.readBytes(1, Integer.MAX_VALUE));
    }

    /**
     * This test verifies a permit stealing bug that existed in the memory implementations.
     * An atomicWait could avoid waiting if it found a "notify permit". This is wrong by definition
     * since atomicNotify can only wake threads in a waiting state.
     *
     * The test creates a thread that calls (A) atomicWait, while the main thread calls atomicNotify and
     * then (B) atomicWait. If the notify call returns one, it means it should have woken (A) since
     * (B) has not yet happened. This means that (B) must timeout since there are no other notify calls
     * involved.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("growableMemoryImplementations")
    public void notifyWakesParkedThreadNotLateArrival(String name, Supplier<Memory> memorySupplier)
            throws Exception {
        final int addr = 0;
        final int sentinel = 42;
        var memory = memorySupplier.get();

        for (int round = 0; round < 200; round++) {
            memory.atomicWriteInt(addr, sentinel);

            var parkedReady = new CountDownLatch(1);

            var parked =
                    new Thread(
                            () -> {
                                parkedReady.countDown();
                                memory.atomicWait(addr, sentinel, 100_000_000L);
                            });
            parked.setDaemon(true);
            parked.start();
            parkedReady.await();
            // This sleep gives the other thread a higher chance of reaching the atomicWait,
            // but setting it too high makes a passing test slow (200 x sleep)
            Thread.sleep(1);

            int woken = memory.atomicNotify(addr, 1);
            if (woken == 0) {
                // we didn't wake up the other thread. This case is not interesting, retry
                continue;
            }

            // Late arrival: this wait MUST timeout as we have only woken ONE thread.
            final int result = memory.atomicWait(addr, sentinel, 0L);

            assertEquals(2, result, "round " + round + ": main thread should have timed out (2)");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    public void atomicsOnNonSharedMemory(String name, Supplier<Memory> memorySupplier) {
        var memory = memorySupplier.get();

        memory.atomicWriteInt(0, 40);
        assertEquals(40, memory.atomicAddInt(0, 2));
        assertEquals(42, memory.atomicReadInt(0));
        assertEquals(42, memory.atomicCmpxchgInt(0, 42, 7));
        assertEquals(7, memory.atomicXchgInt(0, 1));

        memory.atomicWriteLong(8, 1L);
        assertEquals(1L, memory.atomicAddLong(8, 1L));
        assertEquals(2L, memory.atomicReadLong(8));

        memory.atomicWriteShort(16, (short) 3);
        assertEquals((short) 3, memory.atomicAddShort(16, (short) 1));
        assertEquals((short) 4, memory.atomicReadShort(16));

        memory.atomicWriteByte(18, (byte) 5);
        assertEquals((byte) 5, memory.atomicOrByte(18, (byte) 2));
        assertEquals((byte) 7, memory.atomicReadByte(18));
    }

    // e.g. the host updating a non-shared memory from another thread
    @ParameterizedTest(name = "{0}")
    @MethodSource("memoryImplementations")
    public void concurrentAtomicsOnNonSharedMemory(String name, Supplier<Memory> memorySupplier)
            throws Exception {
        final int iterations = 20_000;
        var memory = memorySupplier.get();

        var start = new CountDownLatch(1);
        Runnable adds =
                () -> {
                    awaitQuietly(start);
                    for (int i = 0; i < iterations; i++) {
                        memory.atomicAddInt(0, 1);
                    }
                };
        var first = CompletableFuture.runAsync(adds);
        var second = CompletableFuture.runAsync(adds);
        start.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        assertEquals(2 * iterations, memory.atomicReadInt(0));
    }

    // A waiter reads its value under the word lock, so mix wait/notify with atomics on that word
    @ParameterizedTest(name = "{0}")
    @MethodSource("growableMemoryImplementations")
    public void waitAndNotifyWithAtomicsOnSameWord(String name, Supplier<Memory> memorySupplier)
            throws Exception {
        final int addr = 4;
        var memory = memorySupplier.get();
        memory.atomicWriteInt(addr, 0);

        var stop = new AtomicBoolean();
        // not the common pool: on a 3-core runner it can leave the waiter queued behind adds
        var threads = Executors.newFixedThreadPool(2);
        var adds =
                CompletableFuture.runAsync(
                        () -> {
                            while (!stop.get()) {
                                memory.atomicAddInt(0, 1);
                            }
                        },
                        threads);
        try {
            for (int round = 0; round < 100; round++) {
                var waiter =
                        CompletableFuture.supplyAsync(
                                () -> memory.atomicWait(addr, 0, TimeUnit.SECONDS.toNanos(30)),
                                threads);
                while (memory.atomicNotify(addr, 1) == 0 && !waiter.isDone()) {
                    Thread.onSpinWait();
                }
                assertEquals(0, waiter.get(30, TimeUnit.SECONDS), "round " + round);
            }
        } finally {
            stop.set(true);
            threads.shutdown();
        }
        adds.get(30, TimeUnit.SECONDS);
    }

    /**
     * Overlapping atomics of different sizes must be atomic relative to each other. An i32 add at
     * address 0 and an i16 add at address 2 both read-modify-write bytes 2-3; if they don't
     * exclude each other, one of them overwrites the other's update.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("growableMemoryImplementations")
    public void overlappingAtomicsOfDifferentSizes(String name, Supplier<Memory> memorySupplier)
            throws Exception {
        final int iterations = 20_000;
        var memory = memorySupplier.get();
        memory.atomicWriteInt(0, 0);

        var start = new CountDownLatch(1);
        var ints =
                CompletableFuture.runAsync(
                        () -> {
                            awaitQuietly(start);
                            for (int i = 0; i < iterations; i++) {
                                memory.atomicAddInt(0, 1);
                            }
                        });
        var shorts =
                CompletableFuture.runAsync(
                        () -> {
                            awaitQuietly(start);
                            for (int i = 0; i < iterations; i++) {
                                memory.atomicAddShort(2, (short) 1);
                            }
                        });
        start.countDown();
        CompletableFuture.allOf(ints, shorts).get(30, TimeUnit.SECONDS);

        // little endian: the i32 adds land in the low half, the i16 adds in the high half
        assertEquals(iterations | (iterations << 16), memory.atomicReadInt(0));
    }

    /**
     * Byte atomics must exclude overlapping wider atomics too, also when only some sizes have
     * hardware atomic support. The i32 adds only change the high half, so the low byte at address
     * 1 should hold exactly the i8 adds (modulo 256).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("growableMemoryImplementations")
    public void overlappingByteAndIntAtomics(String name, Supplier<Memory> memorySupplier)
            throws Exception {
        final int iterations = 20_000;
        var memory = memorySupplier.get();
        memory.atomicWriteInt(0, 0);

        var start = new CountDownLatch(1);
        var ints =
                CompletableFuture.runAsync(
                        () -> {
                            awaitQuietly(start);
                            for (int i = 0; i < iterations; i++) {
                                memory.atomicAddInt(0, 1 << 16);
                            }
                        });
        var bytes =
                CompletableFuture.runAsync(
                        () -> {
                            awaitQuietly(start);
                            for (int i = 0; i < iterations; i++) {
                                memory.atomicAddByte(1, (byte) 1);
                            }
                        });
        start.countDown();
        CompletableFuture.allOf(ints, bytes).get(30, TimeUnit.SECONDS);

        assertEquals((iterations << 16) | ((iterations & 0xFF) << 8), memory.atomicReadInt(0));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
