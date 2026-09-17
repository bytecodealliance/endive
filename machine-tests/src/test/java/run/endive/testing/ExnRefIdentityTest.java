package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
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
 * Two live exceptions sharing a tag must stay distinct. Expected values cross-checked against
 * wasmtime 50.0.0-dev.
 */
@Disabled("exnref is an int keyed by tag index; enabled once it becomes an Object")
public class ExnRefIdentityTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/exnref_identity.wat.wasm"));

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
    public void locals(Function<Instance.Builder, Instance.Builder> machineInject) {
        assertEquals(12, instance(machineInject).export("locals").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void globals(Function<Instance.Builder, Instance.Builder> machineInject) {
        assertEquals(12, instance(machineInject).export("globals").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void table(Function<Instance.Builder, Instance.Builder> machineInject) {
        assertEquals(12, instance(machineInject).export("table").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void select(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(1, instance.export("select").apply(1)[0]);
        assertEquals(2, instance.export("select").apply(0)[0]);
    }
}
