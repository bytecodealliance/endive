package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Store;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;

/** `return_call_indirect` to a function of another instance is not supported. */
public class TailCallIndirectCrossModuleTest {

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
        var store = new Store();
        store.instantiate(
                "test",
                imports ->
                        machineInject
                                .apply(Instance.builder(module("export")).withImportValues(imports))
                                .build());
        return store.instantiate(
                "import",
                imports ->
                        machineInject
                                .apply(Instance.builder(module("import")).withImportValues(imports))
                                .build());
    }

    private static WasmModule module(String suffix) {
        return Parser.parse(
                CorpusResources.getResource(
                        "compiled/tail_call_indirect_cross_module-" + suffix + ".wat.wasm"));
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void callIndirect(Function<Instance.Builder, Instance.Builder> machineInject) {
        assertEquals(7L, instance(machineInject).export("call").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void returnCallIndirectIsRejected(
            Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertThrows(WasmEngineException.class, () -> instance.export("tail").apply());
    }
}
