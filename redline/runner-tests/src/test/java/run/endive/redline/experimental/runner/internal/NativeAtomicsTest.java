package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;

/** Host atomics on a native memory have to be atomic against the guest's own. */
public class NativeAtomicsTest {

    private static final int N = 1_000_000;

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/atomic-counter.wat.wasm"));
    private static final NativeCode CODE =
            NativeCompiler.compile(RedlineTarget.detectHost().orElseThrow().triple(), MODULE);

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @AfterAll
    public static void shutdown() {
        POOL.shutdownNow();
    }

    @Test
    public void hostAndGuestIntAddsDoNotLoseIncrements() throws Exception {
        var memory = sharedMemory();
        race(memory, "add32", () -> memory.atomicAddInt(0, 1));
        assertEquals(2 * N, memory.readInt(0));
    }

    @Test
    public void hostAndGuestLongAddsDoNotLoseIncrements() throws Exception {
        var memory = sharedMemory();
        race(memory, "add64", () -> memory.atomicAddLong(16, 1L));
        assertEquals(2L * N, memory.readLong(16));
    }

    @Test
    public void hostAndGuestShortAddsDoNotLoseIncrements() throws Exception {
        var memory = sharedMemory();
        race(memory, "add16", () -> memory.atomicAddShort(10, (short) 1));
        assertEquals((short) (2 * N), memory.readShort(10));
        assertEquals(0, memory.readShort(8), "the other half of the int is untouched");
    }

    @Test
    public void hostAndGuestByteAddsDoNotLoseIncrements() throws Exception {
        var memory = sharedMemory();
        race(memory, "add8", () -> memory.atomicAddByte(13, (byte) 1));
        assertEquals((byte) (2 * N), memory.read(13));
        assertEquals(0, memory.read(12), "neighbouring bytes are untouched");
        assertEquals(0, memory.read(14));
        assertEquals(0, memory.read(15));
    }

    @Test
    public void narrowOperationsOnlyTouchTheirBytes() {
        var memory = sharedMemory();
        memory.writeI32(0, 0x44332211);

        assertEquals((byte) 0x22, memory.atomicXchgByte(1, (byte) 0x99));
        assertEquals((byte) 0x33, memory.atomicCmpxchgByte(2, (byte) 0x00, (byte) 0x77));
        assertEquals((byte) 0x33, memory.atomicCmpxchgByte(2, (byte) 0x33, (byte) 0x77));
        assertEquals((short) 0x9911, memory.atomicOrShort(0, (short) 0x0002));
        assertEquals(0x44779913, memory.atomicReadInt(0));

        assertEquals((short) 0x4477, memory.atomicAndShort(2, (short) 0x00FF));
        assertEquals((short) 0x0077, memory.atomicXorShort(2, (short) 0x0101));
        assertEquals((short) 0x0176, memory.atomicReadShort(2));
        memory.atomicWriteByte(3, (byte) 0xAB);
        assertEquals((byte) 0xAB, memory.atomicReadByte(3));
        assertEquals(0xAB769913, memory.readInt(0));
    }

    @Test
    public void wideOperationsBehaveLikeTheirScalarForms() {
        var memory = sharedMemory();
        memory.atomicWriteLong(8, 0x0F0F0F0F0F0F0F0FL);
        assertEquals(0x0F0F0F0F0F0F0F0FL, memory.atomicAndLong(8, 0x00FF00FF00FF00FFL));
        assertEquals(0x000F000F000F000FL, memory.atomicOrLong(8, 0x1000000000000000L));
        assertEquals(0x100F000F000F000FL, memory.atomicXorLong(8, 0x100F000F000F000FL));
        assertEquals(0L, memory.atomicCmpxchgLong(8, 0L, 5L));
        assertEquals(5L, memory.atomicXchgLong(8, 6L));
        assertEquals(6L, memory.atomicReadLong(8));

        memory.atomicWriteInt(4, 10);
        assertEquals(10, memory.atomicCmpxchgInt(4, 11, 12));
        assertEquals(10, memory.atomicCmpxchgInt(4, 10, 12));
        assertEquals(12, memory.atomicXchgInt(4, 1));
        assertEquals(1, memory.atomicOrInt(4, 6));
        assertEquals(7, memory.atomicAndInt(4, 5));
        assertEquals(5, memory.atomicXorInt(4, 1));
        assertEquals(4, memory.atomicReadInt(4));
    }

    private static Memory sharedMemory() {
        return NativeMachineFactory.createMemory(new MemoryLimits(1, 1, true));
    }

    private static void race(Memory memory, String export, Runnable hostIncrement)
            throws Exception {
        try (var instance = instance(memory)) {
            var start = new CyclicBarrier(2);
            var guest =
                    POOL.submit(
                            () -> {
                                start.await();
                                instance.export(export).apply(N);
                                return null;
                            });
            start.await();
            for (int i = 0; i < N; i++) {
                hostIncrement.run();
            }
            guest.get(60, TimeUnit.SECONDS);
        }
    }

    private static Instance instance(Memory memory) {
        return NativeMachineFactory.builder(MODULE)
                .withPrecompiledCode(CODE)
                .withImportValues(
                        ImportValues.builder()
                                .addMemory(new ImportMemory("env", "memory", memory))
                                .build())
                .build();
    }
}
