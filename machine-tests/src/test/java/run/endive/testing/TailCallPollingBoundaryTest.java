package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.runtime.WasmInterruptedException;
import run.endive.wasm.Parser;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/** Pending tail calls can be resolved outside the outermost trampoline. */
public class TailCallPollingBoundaryTest {
    private static Stream<Arguments> boundaries() {
        Function<Instance, Machine> interpreter = InterpreterMachine::new;
        Function<Instance, Machine> compiler =
                instance ->
                        MachineFactoryCompiler.builder(instance.module())
                                .withInterpreterFallback(InterpreterFallback.FAIL)
                                .compile()
                                .apply(instance);
        return Stream.of("direct", "nested", "direct_ref", "nested_ref")
                .flatMap(
                        export ->
                                Stream.of(
                                        Arguments.of("interpreter", export, interpreter),
                                        Arguments.of("compiler", export, compiler)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("boundaries")
    public void interruptBeforeTailCallDoesNotEnterHost(
            String engine, String export, Function<Instance, Machine> machine) {
        var targetsCalled = new AtomicInteger();
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "env",
                                        "interrupt",
                                        FunctionType.of(List.of(), List.of()),
                                        (inst, args) -> {
                                            Thread.currentThread().interrupt();
                                            return null;
                                        }))
                        .addFunction(
                                target("target", ValType.I64, targetsCalled),
                                target("target_ref", ValType.ExternRef, targetsCalled))
                        .build();
        var module =
                Parser.parse(
                        CorpusResources.getResource(
                                "compiled/tail_call_interrupt_boundary.wat.wasm"));
        var instance =
                Instance.builder(module)
                        .withImportValues(imports)
                        .withMachineFactory(machine)
                        .build();
        var function = instance.export(export);
        try {
            assertThrows(
                    WasmInterruptedException.class,
                    () -> {
                        if (export.endsWith("_ref")) {
                            function.applyWithRefs(new long[0], null);
                        } else {
                            function.apply();
                        }
                    });
            assertTrue(Thread.currentThread().isInterrupted(), "poll cleared the interrupt flag");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, targetsCalled.get(), "entered a host function after interruption");
        assertFalse(instance.isTailCallPending(), "interrupted call left a pending tail call");
        assertEquals(42L, instance.export("value").apply()[0]);
    }

    private static HostFunction target(String name, ValType result, AtomicInteger calls) {
        return new HostFunction(
                "env",
                name,
                FunctionType.of(List.of(), List.of(result)),
                (inst, args) -> {
                    calls.incrementAndGet();
                    return new long[] {42};
                });
    }
}
