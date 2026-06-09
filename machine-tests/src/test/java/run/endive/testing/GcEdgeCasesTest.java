package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

public class GcEdgeCasesTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/gc_edge_cases.wat.wasm"));

    private static Stream<Arguments> machineImplementations() {
        return Stream.of(
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(InterpreterMachine::new)),
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(MachineFactoryCompiler::compile)));
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void structNewDefaultFuncrefIsNull(
            Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var result = instance.export("default_funcref_is_null").apply();
        assertEquals(1, result[0]);
    }

    // TODO: compiler uses int for externref — extern.convert_any round-trip is lossy.
    // Fix requires making externref Object in the compiler (isObjectRef).
    @Test
    public void externRoundTripInterpreter() {
        var instance = Instance.builder(MODULE).withMachineFactory(InterpreterMachine::new).build();
        var result = instance.export("extern_roundtrip").apply();
        assertEquals(42, result[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void makeAndGetPoint(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var makePoint = instance.export("make_point");
        var getX = instance.export("get_x");

        var point = makePoint.applyGc((Object) 99L);
        assertNotNull(point);
        assertNotNull(point[0]);

        var x = getX.applyGc(point[0]);
        assertEquals(99, (int) (Integer) x[0]);
    }
}
