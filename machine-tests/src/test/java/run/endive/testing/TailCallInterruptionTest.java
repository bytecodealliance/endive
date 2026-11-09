package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.runtime.WasmInterruptedException;
import run.endive.wasm.Parser;

public class TailCallInterruptionTest {

    private static Stream<Arguments> cycles() {
        Function<Instance, Machine> interpreter = InterpreterMachine::new;
        Function<Instance, Machine> compiler = MachineFactoryCompiler::compile;
        return Stream.of("direct", "mutual", "indirect", "by_ref", "ref_args", "indirect_ref_args")
                .flatMap(
                        export ->
                                Stream.of(
                                        Arguments.of("interpreter", export, interpreter),
                                        Arguments.of("compiler", export, compiler)));
    }

    private static Instance instance(Function<Instance, Machine> machine) {
        var module =
                Parser.parse(CorpusResources.getResource("compiled/tail_call_cycles.wat.wasm"));
        return Instance.builder(module).withMachineFactory(machine).build();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cycles")
    public void interruptStopsTailCallCycle(
            String engine, String export, Function<Instance, Machine> machine)
            throws InterruptedException {
        var instance = instance(machine);
        var function = instance.export(export);
        // Warm the same machine on this thread before invoking it on another thread.
        assertEquals(42L, instance.export("count_down").apply(10L)[0]);
        var started = new CountDownLatch(1);
        var outcome = new AtomicReference<Throwable>();
        var interrupted = new AtomicReference<Boolean>();
        var worker =
                new Thread(
                        () -> {
                            started.countDown();
                            try {
                                function.apply();
                            } catch (Throwable t) {
                                outcome.set(t);
                            } finally {
                                interrupted.set(Thread.currentThread().isInterrupted());
                            }
                        });
        worker.setDaemon(true);
        worker.start();
        started.await();
        TimeUnit.MILLISECONDS.sleep(100);

        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(worker.isAlive(), "tail call cycle still running after interrupt");
        assertInstanceOf(WasmInterruptedException.class, outcome.get());
        assertTrue(interrupted.get(), "poll must not clear the interrupt flag");
        assertFalse(instance.isTailCallPending(), "interrupted call left a pending tail call");
        assertEquals(42L, instance.export("count_down").apply(10L)[0]);
    }

    private static Stream<Arguments> engines() {
        return Stream.of(
                Arguments.of("interpreter", (Function<Instance, Machine>) InterpreterMachine::new),
                Arguments.of(
                        "compiler", (Function<Instance, Machine>) MachineFactoryCompiler::compile));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void alreadyInterruptedTailCallPreservesFlag(
            String engine, Function<Instance, Machine> machine) {
        var instance = instance(machine);
        try {
            Thread.currentThread().interrupt();
            assertThrows(
                    WasmInterruptedException.class,
                    () -> instance.export("count_down").apply(100L));
            assertTrue(Thread.currentThread().isInterrupted(), "poll cleared the interrupt flag");
        } finally {
            Thread.interrupted();
        }
        assertFalse(instance.isTailCallPending());
        assertEquals(42L, instance.export("count_down").apply(10L)[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void finiteTailRecursionRunsInConstantStack(
            String engine, Function<Instance, Machine> machine) {
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(42L, instance(machine).export("count_down").apply(1_000_000L)[0]);
    }
}
