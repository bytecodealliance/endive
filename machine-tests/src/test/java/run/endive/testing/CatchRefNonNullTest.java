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

/** Tests for `catch_ref` and `catch_all_ref` sending a non-nullable `(ref exn)`. */
public class CatchRefNonNullTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/catch_ref_non_null.wat.wasm"));

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
    public void catchRefNonNull(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(42, instance.export("catch-ref-non-null").apply(42)[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void catchAllRefNonNull(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(42, instance.export("catch-all-ref-non-null").apply(42)[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void rethrowNonNull(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(42, instance.export("rethrow-non-null").apply(42)[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void catchRefNullable(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(42, instance.export("catch-ref-nullable").apply(42)[0]);
    }
}
