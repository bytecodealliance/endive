package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
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
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/** Tail calls made after a nested call that itself ended in a tail call. */
public class TailCallNestedTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/tail_call_nested.wat.wasm"));

    private static final List<String> EXPORTS =
            List.of(
                    "return-call",
                    "return-call-indirect",
                    "return-call-indirect-subtype",
                    "return-call-struct-arg",
                    "return-call-ref",
                    "return-call-import",
                    "return-call-ref-import",
                    "return-call-after-catch");

    private static Stream<Arguments> machineImplementations() {
        return EXPORTS.stream()
                .flatMap(
                        name ->
                                Stream.of(
                                        Arguments.of(
                                                "interpreter",
                                                (Function<Instance, Machine>)
                                                        InterpreterMachine::new,
                                                name),
                                        Arguments.of(
                                                "compiler",
                                                (Function<Instance, Machine>)
                                                        MachineFactoryCompiler::compile,
                                                name)));
    }

    private static Instance.Builder builder() {
        var hImport =
                new HostFunction(
                        "env",
                        "h_import",
                        FunctionType.of(List.of(), List.of(ValType.I32)),
                        (inst, args) -> new long[] {42});
        return Instance.builder(MODULE)
                .withImportValues(ImportValues.builder().addFunction(hImport).build());
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("machineImplementations")
    public void tailCallAfterNestedTailCall(
            String machine, Function<Instance, Machine> machineFactory, String export) {
        var instance = builder().withMachineFactory(machineFactory).build();
        assertEquals(42L, instance.export(export).apply()[0]);
    }

    private static final class CallStackInterpreter extends InterpreterMachine {
        CallStackInterpreter(Instance instance) {
            super(instance);
        }

        int callStackSize() {
            return callStack.size();
        }
    }

    private static Stream<String> exports() {
        return EXPORTS.stream();
    }

    @ParameterizedTest
    @MethodSource("exports")
    public void noFramesLeftAfterReturn(String export) {
        var instance = builder().withMachineFactory(CallStackInterpreter::new).build();
        var machine = (CallStackInterpreter) instance.getMachine();
        instance.export(export).apply();
        assertEquals(0, machine.callStackSize());
    }
}
