package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.runtime.WasmInterruptedException;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;

public class TailCallInterruptionTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/tail_call_cycles.wat.wasm"));

    private static Stream<Arguments> cycles() {
        return Stream.of("direct", "mutual", "indirect", "by_ref", "direct_refs", "indirect_refs")
                .flatMap(
                        export ->
                                Stream.of(
                                        Arguments.of(
                                                "interpreter",
                                                export,
                                                (Function<Instance, Machine>)
                                                        InterpreterMachine::new),
                                        Arguments.of(
                                                "compiler",
                                                export,
                                                (Function<Instance, Machine>)
                                                        MachineFactoryCompiler::compile)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cycles")
    public void interruptStopsTailCallCycle(
            String engine, String export, Function<Instance, Machine> machine)
            throws InterruptedException {
        // counted down from inside Wasm, so only the cycle itself can see the interrupt
        var started = new CountDownLatch(1);
        var startedImport =
                new HostFunction(
                        "env",
                        "started",
                        FunctionType.empty(),
                        (inst, args) -> {
                            started.countDown();
                            return null;
                        });
        var function =
                Instance.builder(MODULE)
                        .withImportValues(ImportValues.builder().addFunction(startedImport).build())
                        .withMachineFactory(machine)
                        .build()
                        .export(export);
        var outcome = new AtomicReference<Throwable>();
        var worker =
                new Thread(
                        () -> {
                            try {
                                if (export.endsWith("_refs")) {
                                    function.applyWithRefs(new long[] {0}, new Object[] {null});
                                } else {
                                    function.apply();
                                }
                            } catch (Throwable t) {
                                outcome.set(t);
                            }
                        });
        worker.setDaemon(true);
        worker.start();
        assertTrue(started.await(10, TimeUnit.SECONDS), "tail call cycle never started");

        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(worker.isAlive(), "tail call cycle still running after interrupt");
        assertInstanceOf(WasmInterruptedException.class, outcome.get());
    }
}
