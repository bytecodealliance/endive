package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * `ref.test` against the exception hierarchy and `br_table` carrying an exnref. Expected values
 * cross-checked against wasmtime 50.0.0-dev.
 */
public class ExnRefOpsTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/exnref_ops.wat.wasm"));

    private static Stream<Arguments> machineImplementations() {
        return Stream.of(
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(InterpreterMachine::new)),
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(MachineFactoryCompiler::compile)));
    }

    private static Instance instance(Function<Instance.Builder, Instance.Builder> machineInject) {
        return machineInject
                .apply(Instance.builder(MODULE).withImportValues(ImportValues.builder().build()))
                .build();
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void refTestExn(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(1, instance.export("test-exn-nonnull").apply()[0]);
        assertEquals(0, instance.export("test-exn-null").apply()[0]);
        assertEquals(1, instance.export("test-nullable-exn-null").apply()[0]);
    }

    /** noexn is uninhabited, so a real exception is never an instance of it. */
    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void refTestNoExn(Function<Instance.Builder, Instance.Builder> machineInject) {
        assertEquals(0, instance(machineInject).export("test-noexn-nonnull").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void brTableCarriesExnRef(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(10, instance.export("br-table").apply(0)[0]);
        assertEquals(20, instance.export("br-table").apply(1)[0]);
    }
}
