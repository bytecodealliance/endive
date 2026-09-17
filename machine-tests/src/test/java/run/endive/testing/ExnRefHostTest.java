package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.CallResult;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.WasmException;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/** An exnref crossing to a host function and back keeps its identity. */
public class ExnRefHostTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/exnref_host.wat.wasm"));

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
        var roundtrip =
                new HostFunction(
                        "host",
                        "roundtrip",
                        FunctionType.of(List.of(ValType.ExnRef), List.of(ValType.ExnRef)),
                        new run.endive.runtime.WasmFunctionHandle() {
                            @Override
                            public long[] apply(Instance instance, long... args) {
                                throw new UnsupportedOperationException("use applyWithRefs");
                            }

                            @Override
                            public CallResult applyWithRefs(
                                    Instance instance, long[] args, Object[] refArgs) {
                                // the host sees the real exception object, not an index
                                assertInstanceOf(WasmException.class, refArgs[0]);
                                return CallResult.of(new long[1], new Object[] {refArgs[0]});
                            }
                        });
        return machineInject
                .apply(
                        Instance.builder(MODULE)
                                .withImportValues(
                                        ImportValues.builder().addFunction(roundtrip).build()))
                .build();
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void roundTripThroughHost(Function<Instance.Builder, Instance.Builder> machineInject) {
        assertEquals(42, instance(machineInject).export("roundtrip-payload").apply(42)[0]);
    }
}
