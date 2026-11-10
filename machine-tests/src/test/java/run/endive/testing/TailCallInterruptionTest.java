package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
        return Stream.of("direct", "mutual", "indirect", "by_ref")
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
        var function = instance(machine).export(export);
        var started = new CountDownLatch(1);
        var outcome = new AtomicReference<Throwable>();
        var worker =
                new Thread(
                        () -> {
                            started.countDown();
                            try {
                                function.apply();
                            } catch (Throwable t) {
                                outcome.set(t);
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
    }

    private static Stream<Arguments> engines() {
        return Stream.of(
                Arguments.of("interpreter", (Function<Instance, Machine>) InterpreterMachine::new),
                Arguments.of(
                        "compiler", (Function<Instance, Machine>) MachineFactoryCompiler::compile));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void finiteTailRecursionRunsInConstantStack(
            String engine, Function<Instance, Machine> machine) {
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(42L, instance(machine).export("count_down").apply(1_000_000L)[0]);
    }
}
