package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Tests for GC ref review fixes:
 * - Bug 2: null GC ref through block boundary (doControlTransfer)
 * - Bug 7: null GC ref in exception (pushExceptionArgs)
 * - Bug 8: table.fill integer overflow
 */
public class GcReviewFixesTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/gc_review_fixes.wat.wasm"));

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

    // Bug 2: null GC ref must survive block boundary
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void nullRefThroughBlock(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var result = instance.export("null_ref_through_block").apply();
        assertEquals(1, result[0], "null ref should be preserved through block");
    }

    // Bug 2: null GC ref through nested blocks
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void nullRefNestedBlocks(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var result = instance.export("null_ref_nested_blocks").apply();
        assertEquals(1, result[0], "null ref should be preserved through nested blocks");
    }

    // Bug 2 regression: non-null GC ref through block
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void nonNullRefThroughBlock(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var result = instance.export("nonnull_ref_through_block").apply();
        assertEquals(42, result[0], "non-null ref value should be preserved through block");
    }

    // Bug 7: null GC ref through exception catch
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void nullRefException(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var result = instance.export("null_ref_exception").apply();
        assertEquals(1, result[0], "null ref should be preserved through exception");
    }

    // Bug 7 regression: non-null GC ref through exception
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void nonNullRefException(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var result = instance.export("nonnull_ref_exception").apply();
        assertEquals(99, result[0], "non-null ref value should be preserved through exception");
    }

    // Bug 8: table.fill bounds (validates no integer overflow)
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void tableFillBounds(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var result = instance.export("table_fill_bounds").apply();
        assertEquals(1, result[0], "table.fill should succeed for valid bounds");
    }

    // Bug 1: externref functions should throw from apply() (isObjectRef, not isGcReference)
    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void externrefApplyGuard(
            String name, Function<Instance.Builder, Instance.Builder> machineInject) {
        // The gc_edge_cases module has make_point which takes/returns GC refs.
        // After the fix, apply() should throw for functions with any ObjectRef params/returns.
        var module = Parser.parse(CorpusResources.getResource("compiled/gc_edge_cases.wat.wasm"));
        var instance = machineInject.apply(Instance.builder(module)).build();
        // make_point has (ref $Point) return - should throw from apply()
        var makePoint = instance.export("make_point");
        try {
            makePoint.apply(99);
            // If we get here, the guard didn't fire (bug 1 not fixed)
            throw new AssertionError("apply() should throw for GC ref returns");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }
}
