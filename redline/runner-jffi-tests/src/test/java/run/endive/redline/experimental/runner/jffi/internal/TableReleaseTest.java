package run.endive.redline.experimental.runner.jffi.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.ImportTable;
import run.endive.runtime.ImportValues;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/**
 * A table's buffer is off-heap, so the garbage collector never reclaims it and
 * closing the instance has to. Which tables that covers is the whole question: one
 * the module declares belongs to the instance, and one it borrowed through an
 * import belongs to whoever created it and may still back something else.
 */
public class TableReleaseTest {

    @Test
    public void closingReleasesATableTheModuleDeclares() {
        var module = Parser.parse(CorpusResources.getResource("compiled/big-table.wat.wasm"));

        JffiNativeTable table;
        // no call first: the table belongs to the instance whether or not it ever ran
        try (var instance = build(module, null)) {
            table = (JffiNativeTable) instance.table(0);
            assertFalse(table.isFreed(), "still in use");
        }

        assertTrue(table.isFreed(), "a table the module declares dies with the instance");
    }

    @Test
    public void closingKeepsATableTheModuleImported() {
        var module = Parser.parse(CorpusResources.getResource("compiled/imported-table.wat.wasm"));

        var borrowed =
                (JffiNativeTable)
                        JffiNativeMachineFactory.createImportTable(
                                new Table(ValType.FuncRef, new TableLimits(4, 4)),
                                Value.REF_NULL_VALUE);
        var imports =
                ImportValues.builder().addTable(new ImportTable("env", "table", borrowed)).build();

        try (var instance = build(module, imports)) {
            instance.export("noop").apply();
            assertThrows(
                    IllegalStateException.class,
                    borrowed::close,
                    "an open instance still reads the table through a raw address");
        }

        assertFalse(
                borrowed.isFreed(),
                "an imported table belongs to its creator and may back other instances");
        borrowed.close();
        assertTrue(borrowed.isFreed());
    }

    private static run.endive.runtime.Instance build(WasmModule module, ImportValues imports) {
        var builder =
                JffiNativeMachineFactory.builder(module)
                        .withPrecompiledCode(
                                NativeCompiler.compile(
                                        RedlineTarget.detectHost().orElseThrow().triple(), module));
        if (imports != null) {
            builder.withImportValues(imports);
        }
        return builder.build();
    }
}
