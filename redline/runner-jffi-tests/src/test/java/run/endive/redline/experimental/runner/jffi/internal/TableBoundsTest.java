package run.endive.redline.experimental.runner.jffi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.testing.NativeInstanceBuilder;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.Value;

/** Compiled table code reaches the native buffer directly, so nothing may take it past its end. */
public class TableBoundsTest {

    private static final String GROWER =
            "(module\n"
                    + "  (table $unbounded 1 funcref)\n"
                    + "  (table $bounded 1 5000000 funcref)\n"
                    + "  (func (export \"growUnbounded\") (param i32) (result i32)\n"
                    + "    (table.grow $unbounded (ref.null func) (local.get 0)))\n"
                    + "  (func (export \"growBounded\") (param i32) (result i32)\n"
                    + "    (table.grow $bounded (ref.null func) (local.get 0))))\n";

    /** Its fourth function sits in the table it exports. */
    private static final String OWNER =
            "(module\n"
                    + "  (table (export \"tab\") 1 funcref)\n"
                    + "  (func $f0) (func $f1) (func $f2) (func $f3)\n"
                    + "  (elem (i32.const 0) $f3))\n";

    /** Copies the owner's entry into a table of its own, where id 3 is no function of its. */
    private static final String COPIER =
            "(module\n"
                    + "  (import \"owner\" \"tab\" (table 1 funcref))\n"
                    + "  (table $own 1 funcref)\n"
                    + "  (func (export \"copy\")\n"
                    + "    (table.set $own (i32.const 0) (table.get 0 (i32.const 0)))))\n";

    @Test
    public void nativeGrowStopsAtTheBuffer() {
        try (var instance = nativeInstance(GROWER, new Store())) {
            assertEquals(-1, grow(instance, "growUnbounded", 2_000_000));
            assertEquals(-1, grow(instance, "growBounded", 2_000_000));
            assertEquals(1, grow(instance, "growUnbounded", 10));
            assertEquals(11, instance.table(0).size());
        }
    }

    @Test
    public void hostGrowDoesNotOverflow() {
        try (var instance = nativeInstance(GROWER, new Store())) {
            var table = instance.table(0);
            assertEquals(-1, table.grow(Integer.MAX_VALUE, Value.REF_NULL_VALUE, instance));
            assertEquals(1, table.size());
        }
    }

    @Test
    public void settingAnotherInstancesFunctionIdTraps() {
        var store = new Store();
        try (var owner = nativeInstance(OWNER, store)) {
            store.register("owner", owner);
            try (var copier = nativeInstance(COPIER, store)) {
                var e =
                        assertThrows(
                                WasmEngineException.class, () -> copier.export("copy").apply());
                assertTrue(e.getMessage().contains("undefined element"), e.getMessage());
            }
        }
    }

    @Test
    public void hostWritesOfAnUnknownFunctionIdThrow() {
        try (var owner = nativeInstance(OWNER, new Store())) {
            var e =
                    assertThrows(
                            WasmEngineException.class, () -> owner.table(0).setRef(0, 99, owner));
            assertTrue(e.getMessage().contains("undefined element"), e.getMessage());
        }
    }

    private static int grow(Instance instance, String export, int delta) {
        return (int) instance.export(export).apply(delta)[0];
    }

    private static Instance nativeInstance(String wat, Store store) {
        return NativeInstanceBuilder.builder(Parser.parse(Wat2Wasm.parse(wat)))
                .withImportValues(store.toImportValues())
                .build();
    }
}
