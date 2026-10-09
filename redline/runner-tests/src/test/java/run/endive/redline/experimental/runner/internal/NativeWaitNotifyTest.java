package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.WasmInterruptedException;
import run.endive.wasm.Parser;
import run.endive.wasm.types.MemoryLimits;

public class NativeWaitNotifyTest {

    // Ported from the runtime's MemoryTest (#107): a late wait can't take the parked one's wakeup
    @Test
    public void notifyWakesTheParkedThreadNotALateArrival() throws Exception {
        final int addr = 0;
        final int sentinel = 42;
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 1, true));

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
            Thread.sleep(1);

            if (memory.atomicNotify(addr, 1) == 0) {
                continue;
            }
            assertEquals(2, memory.atomicWait(addr, sentinel, 0L), "round " + round);
        }
    }

    @Test
    public void interruptingAGuestBlockedInAWaitStopsIt() throws Exception {
        var module = Parser.parse(CorpusResources.getResource("compiled/threads-example.wat.wasm"));
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 1, true));
        try (var instance =
                NativeMachineFactory.builder(module)
                        .withPrecompiledCode(
                                NativeCompiler.compile(
                                        RedlineTarget.detectHost().orElseThrow().triple(), module))
                        .withImportValues(
                                ImportValues.builder()
                                        .addMemory(new ImportMemory("env", "memory", memory))
                                        .build())
                        .build()) {
            // locked, so lockMutex parks in memory.atomic.wait32
            memory.atomicWriteInt(0, 1);
            var failure = new AtomicReference<Throwable>();
            var blocked =
                    new Thread(
                            () -> {
                                try {
                                    instance.export("lockMutex").apply(0);
                                } catch (Throwable t) {
                                    failure.set(t);
                                }
                            });
            blocked.setDaemon(true);
            blocked.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (blocked.getState() != Thread.State.WAITING
                    && blocked.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(System.nanoTime() < deadline, "never parked");
                Thread.sleep(1);
            }
            blocked.interrupt();
            blocked.join(TimeUnit.SECONDS.toMillis(30));

            assertTrue(!blocked.isAlive(), "the interrupted guest kept running");
            assertInstanceOf(WasmInterruptedException.class, failure.get());
        }
    }
}
