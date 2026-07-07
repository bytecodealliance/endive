package endive.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import run.endive.annotations.WasmModuleInterface;
import run.endive.runtime.CallResult;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

/**
 * Integration test covering every meaningful permutation of primitive (long) and
 * GC ref (Object) in both parameter and return positions, for exports and imports.
 *
 * <p>The annotation processor must generate correct code for all of these —
 * if any permutation is unsupported, this test will fail at compile time
 * (annotation processor crash) or at run time (wrong values / ClassCastException).
 */
class GcRefPermutationsTest {

    @WasmModuleInterface("gc_ref_permutations.wat.wasm")
    static class GcRefModule implements GcRefModule_ModuleImports, GcRefModule_Env {
        private final Instance instance;
        private final GcRefModule_ModuleExports exports;

        GcRefModule() {
            var module =
                    Parser.parse(
                            GcRefPermutationsTest.class.getResourceAsStream(
                                    "/gc_ref_permutations.wat.wasm"));
            this.instance = Instance.builder(module).withImportValues(toImportValues()).build();
            this.exports = new GcRefModule_ModuleExports(instance);
        }

        @Override
        public GcRefModule_Env env() {
            return this;
        }

        @Override
        public Object makePoint(int x, int y) {
            return exports.longToRef(x);
        }

        @Override
        public int getX(Object point) {
            return exports.refToLong(point);
        }

        @Override
        public int sumX(Object p1, Object p2) {
            return exports.refToLong(p1) + exports.refToLong(p2);
        }

        @Override
        public int addToX(int n, Object point) {
            return n + exports.refToLong(point);
        }

        @Override
        public Object swapXy(Object point) {
            return exports.refToRef(point);
        }
    }

    private static GcRefModule module;

    @BeforeAll
    static void setUp() {
        module = new GcRefModule();
    }

    // ================================================================
    //  EXPORT tests — single param, single return
    // ================================================================

    @Nested
    class SingleParamSingleReturn {

        @Test
        void longToLong() {
            assertEquals(42, module.exports.longToLong(42));
        }

        @Test
        void refToLong() {
            Object point = module.exports.longToRef(7);
            assertEquals(7, module.exports.refToLong(point));
        }

        @Test
        void longToRef() {
            Object point = module.exports.longToRef(3);
            assertNotNull(point);
            assertEquals(3, module.exports.getXExport(point));
            assertEquals(3, module.exports.getYExport(point));
        }

        @Test
        void refToRef() {
            // long_to_ref(5) creates point(5,5), ref_to_ref swaps x/y
            Object original = module.exports.longToRef(5);
            Object swapped = module.exports.refToRef(original);
            assertNotNull(swapped);
            assertEquals(5, module.exports.getXExport(swapped));
            assertEquals(5, module.exports.getYExport(swapped));
        }
    }

    // ================================================================
    //  EXPORT tests — multi-param, single return
    // ================================================================

    @Nested
    class MultiParamSingleReturn {

        @Test
        void longRefToLong() {
            Object point = module.exports.longToRef(10);
            // long_ref_to_long(n, point) = n + point.x
            assertEquals(13, module.exports.longRefToLong(3, point));
        }

        @Test
        void refLongToLong() {
            Object point = module.exports.longToRef(10);
            // ref_long_to_long(point, n) = point.x + n
            assertEquals(15, module.exports.refLongToLong(point, 5));
        }

        @Test
        void refRefToLong() {
            Object p1 = module.exports.longToRef(3);
            Object p2 = module.exports.longToRef(7);
            // ref_ref_to_long(p1, p2) = p1.x + p2.x
            assertEquals(10, module.exports.refRefToLong(p1, p2));
        }

        @Test
        void longRefToRef() {
            Object point = module.exports.longToRef(10);
            // long_ref_to_ref(n, point) = new_point(n, point.y)
            Object result = module.exports.longRefToRef(99, point);
            assertNotNull(result);
            assertEquals(99, module.exports.getXExport(result));
            assertEquals(10, module.exports.getYExport(result));
        }

        @Test
        void refLongRefToLong() {
            Object p1 = module.exports.longToRef(1);
            Object p2 = module.exports.longToRef(3);
            // ref_long_ref_to_long(p1, n, p2) = p1.x + n + p2.x
            assertEquals(104, module.exports.refLongRefToLong(p1, 100, p2));
        }
    }

    // ================================================================
    //  EXPORT tests — multi-return (long + ref combinations)
    // ================================================================

    @Nested
    class MultiReturn {

        @Test
        void longToLongRef() {
            // returns (i32, ref $point) — the i32 is the input, the ref is point(input, input)
            CallResult cr = module.exports.longToLongRef(42);
            assertNotNull(cr);
            assertEquals(42, cr.longResult(0));
            Object point = cr.refResult(1);
            assertNotNull(point);
            assertEquals(42, module.exports.getXExport(point));
        }

        @Test
        void longToRefLong() {
            // returns (ref $point, i32) — ref is point(input, input), i32 is input
            CallResult cr = module.exports.longToRefLong(7);
            assertNotNull(cr);
            Object point = cr.refResult(0);
            assertNotNull(point);
            assertEquals(7, module.exports.getXExport(point));
            assertEquals(7, cr.longResult(1));
        }

        @Test
        void longToRefRef() {
            // returns (ref $point, ref $point)
            CallResult cr = module.exports.longToRefRef(5);
            assertNotNull(cr);
            Object p1 = cr.refResult(0);
            Object p2 = cr.refResult(1);
            assertNotNull(p1);
            assertNotNull(p2);
            // first point: (5, 0), second point: (0, 5)
            assertEquals(5, module.exports.getXExport(p1));
            assertEquals(0, module.exports.getYExport(p1));
            assertEquals(0, module.exports.getXExport(p2));
            assertEquals(5, module.exports.getYExport(p2));
        }
    }

    // ================================================================
    //  EXPORT tests — void / no-params edge cases
    // ================================================================

    @Nested
    class VoidEdgeCases {

        @Test
        void refToVoid() {
            Object point = module.exports.longToRef(1);
            module.exports.refToVoid(point);
            // no assertion needed — just must not throw
        }

        @Test
        void voidToRef() {
            Object point = module.exports.voidToRef();
            assertNotNull(point);
            assertEquals(100, module.exports.getXExport(point));
            assertEquals(200, module.exports.getYExport(point));
        }
    }

    // ================================================================
    //  IMPORT tests — via export wrappers
    // ================================================================

    @Nested
    class ImportPermutations {

        @Test
        void makePoint_longParamsRefReturn() {
            // import make_point(i32, i32) → (ref $point)
            assertEquals(3, module.exports.testMakePoint(3, 4));
        }

        @Test
        void getX_refParamLongReturn() {
            // import get_x((ref null $point)) → i32
            assertEquals(5, module.exports.testGetX(5, 9));
        }

        @Test
        void sumX_multiRefParams() {
            // import sum_x((ref, ref)) → i32
            assertEquals(13, module.exports.testSumX(6, 7));
        }

        @Test
        void addToX_mixedParams() {
            // import add_to_x(i32, (ref null $point)) → i32
            assertEquals(30, module.exports.testAddToX(10, 20));
        }

        @Test
        void swapXy_refParamRefReturn() {
            // import swap_xy((ref null $point)) → (ref $point)
            // swap_xy swaps x/y, so get_x of swapped(3,7) should give 7
            assertEquals(7, module.exports.testSwapXy(3, 7));
        }
    }
}
