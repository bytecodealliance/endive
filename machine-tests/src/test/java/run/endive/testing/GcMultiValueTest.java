package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Tests for multi-value returns mixing GC refs and numerics.
 */
public class GcMultiValueTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/gc_multivalue.wat.wasm"));

    private static Stream<Arguments> machineImplementations() {
        return Stream.of(
                Arguments.of(
                        "interpreter",
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(InterpreterMachine::new)),
                Arguments.of(
                        "compiler",
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(MachineFactoryCompiler::compile)));
    }

    // (result i32 (ref $Point)) -- numeric then ref
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void intThenRef(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var fn = instance.export("int_then_ref");
        var getX = instance.export("get_x");

        var cr = fn.applyWithRefs(new long[] {42}, null);
        assertEquals(42, cr.longResult(0), "numeric result");
        Object ref = cr.refResult(1);
        assertNotNull(ref, "ref result should not be null");
        // Verify the ref is usable
        var xResult = getX.applyWithRefs(new long[] {0}, new Object[] {ref});
        assertEquals(42, xResult.longResult(0), "get_x from returned ref");
    }

    // (result (ref $Point) i32) -- ref then numeric
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void refThenInt(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var fn = instance.export("ref_then_int");
        var getX = instance.export("get_x");

        var cr = fn.applyWithRefs(new long[] {99}, null);
        Object ref = cr.refResult(0);
        assertNotNull(ref, "ref result should not be null");
        assertEquals(99, cr.longResult(1), "numeric result");
        // Verify the ref is usable
        var xResult = getX.applyWithRefs(new long[] {0}, new Object[] {ref});
        assertEquals(99, xResult.longResult(0), "get_x from returned ref");
    }

    // (result (ref $Point) (ref $Point)) -- two refs
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void twoRefs(String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var fn = instance.export("two_refs");
        var getX = instance.export("get_x");

        var cr = fn.applyWithRefs(new long[] {7}, null);
        Object ref0 = cr.refResult(0);
        Object ref1 = cr.refResult(1);
        assertNotNull(ref0, "first ref should not be null");
        assertNotNull(ref1, "second ref should not be null");
        var x0 = getX.applyWithRefs(new long[] {0}, new Object[] {ref0});
        var x1 = getX.applyWithRefs(new long[] {0}, new Object[] {ref1});
        assertEquals(7, x0.longResult(0), "get_x from first ref");
        assertEquals(7, x1.longResult(0), "get_x from second ref");
    }

    // (result i32 i32 (ref $Point)) -- two numerics + ref
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void twoIntsRef(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var fn = instance.export("two_ints_ref");
        var getX = instance.export("get_x");

        var cr = fn.applyWithRefs(new long[] {11}, null);
        assertEquals(11, cr.longResult(0), "first numeric result");
        assertEquals(11, cr.longResult(1), "second numeric result");
        Object ref = cr.refResult(2);
        assertNotNull(ref, "ref result should not be null");
        var xResult = getX.applyWithRefs(new long[] {0}, new Object[] {ref});
        assertEquals(11, xResult.longResult(0), "get_x from returned ref");
    }
}
