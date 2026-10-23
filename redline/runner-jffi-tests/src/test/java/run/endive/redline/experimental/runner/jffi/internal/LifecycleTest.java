package run.endive.redline.experimental.runner.jffi.internal;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.ImportFactory;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.internal.NativeResourceCount;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiMachineFactoryProvider;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportGlobal;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportTable;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.Parser;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/** Close frees all an instance made, nothing it borrowed, and never what is still in use. */
public class LifecycleTest {

    @Test
    public void closingTwiceIsSafe() {
        var instance = build(parse("trap-stops-execution"), ImportValues.empty());
        instance.close();
        instance.close();
    }

    @Test
    public void aMemoryTheModuleDefinesStillWorksBeforeClose() {
        try (var instance = build(parse("trap-stops-execution"), ImportValues.empty())) {
            instance.memory().writeI32(0, 0x5A5A5A5A);
            assertEquals(0x5A5A5A5A, instance.memory().readInt(0));
        }
    }

    @Test
    public void closingFreesEverythingTheInstanceMade() {
        var withMemory = parse("trap-stops-execution");
        var withTable = parse("big-table");
        var withMemoryCode = compile(withMemory);
        var withTableCode = compile(withTable);
        int before = NativeResourceCount.live();
        for (int i = 0; i < 100; i++) {
            try (var instance = build(withMemory, withMemoryCode)) {
                instance.memory().writeI32(0, i);
            }
            try (var instance = build(withTable, withTableCode)) {
                instance.export("noop").apply();
            }
        }
        assertEquals(before, NativeResourceCount.live());
    }

    @Test
    public void aTableTheModuleDefinesIsFreedWithoutAnyCall() {
        TableInstance table;
        try (var instance = build(parse("big-table"), ImportValues.empty())) {
            table = instance.table(0);
            assertEquals(100000, table.size());
        }
        assertThrows(IllegalStateException.class, table::size);
    }

    @Test
    public void usingAClosedInstanceThrowsRatherThanCrashing() {
        var instance = build(parse("trap-stops-execution"), ImportValues.empty());
        var memory = instance.memory();
        instance.close();

        assertThrows(WasmEngineException.class, () -> instance.export("storeAfterTrap").apply());
        assertThrows(IllegalStateException.class, () -> memory.readInt(0));
        assertThrows(IllegalStateException.class, () -> memory.writeI32(0, 1));
    }

    @Test
    public void closingFromInsideACallIsRefused() {
        Instance[] self = new Instance[1];
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "boom",
                                        FunctionType.of(List.of(), List.of()),
                                        (inst, args) -> {
                                            self[0].close();
                                            return null;
                                        }))
                        .build();
        var instance = build(parse("host-throw-stops-execution"), imports);
        self[0] = instance;

        assertThrows(IllegalStateException.class, () -> instance.export("callBoom").apply());
        assertEquals(0, instance.memory().readInt(0), "nothing was freed");
        instance.close();
        assertThrows(WasmEngineException.class, () -> instance.export("callBoom").apply());
    }

    @Test
    public void closingWhileAnotherThreadIsInACallIsRefused() throws InterruptedException {
        var running = new CountDownLatch(1);
        var noParams = FunctionType.of(List.of(), List.of());
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction("host", "reenter", noParams, (inst, args) -> null))
                        .addFunction(
                                new HostFunction(
                                        "host", "raiseFlag", noParams, (inst, args) -> null))
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "tick",
                                        noParams,
                                        (inst, args) -> {
                                            running.countDown();
                                            return null;
                                        }))
                        .build();
        var instance = build(parse("reentrant-interrupt"), imports);
        var caller = new Thread(() -> instance.export("spin").apply());
        caller.setDaemon(true);
        caller.setUncaughtExceptionHandler((t, e) -> {});
        caller.start();
        assertTrue(running.await(10, SECONDS), "the call never started");

        assertThrows(IllegalStateException.class, instance::close);

        caller.interrupt();
        caller.join(SECONDS.toMillis(10));
        assertFalse(caller.isAlive(), "the call was not interrupted");
        instance.close();
    }

    @Test
    public void globalsKeepTheirValueAfterClose() {
        GlobalInstance exported;
        try (var instance = build(parse("all-exports"), ImportValues.empty())) {
            exported = instance.exports().global("glob1");
        }
        assertEquals(42L, exported.getValue());

        var counter =
                JffiNativeMachineFactory.createImportGlobal(10L, ValType.I32, MutabilityType.Var);
        try (var instance = buildImportedMutables(memory(), table(), counter)) {
            instance.export("run").apply();
        }
        assertEquals(11L, counter.getValue(), "the host's global outlives the instance");
        try (var instance = buildImportedMutables(memory(), table(), counter)) {
            instance.export("run").apply();
        }
        assertEquals(12L, counter.getValue(), "and a new instance can import it again");
    }

    @Test
    public void instancesFromOneBuilderKeepTheirOwnGlobals() {
        var module = parse("exported-mutable-global");
        var builder =
                JffiNativeMachineFactory.builder(module)
                        .withPrecompiledCode(compile(module))
                        .toInstanceBuilder();
        var first = builder.build();
        var second = builder.build();

        assertEquals(1L, first.export("increment").apply()[0]);
        assertEquals(1L, second.export("increment").apply()[0]);
        assertEquals(2L, first.export("increment").apply()[0]);
        first.close();
        assertEquals(2L, second.export("increment").apply()[0]);
        second.close();
    }

    @Test
    public void anExportedMemoryOutlivesItsImporters() {
        var exporter = build(parse("trap-stops-execution"), ImportValues.empty());
        var importer = buildImportedMutables(exporter.memory(), table(), global());
        importer.export("run").apply();

        assertThrows(IllegalStateException.class, exporter::close);
        assertEquals(23130, exporter.memory().readInt(0), "refusing changed nothing");

        importer.close();
        exporter.close();
    }

    @Test
    public void anExportedTableOutlivesItsImporters() {
        var exporter = build(parse("call_indirect-export"), ImportValues.empty());
        var imports =
                ImportValues.builder()
                        .addTable(
                                new ImportTable(
                                        "test",
                                        "shared-table",
                                        exporter.exports().table("shared-table")))
                        .build();
        var importer = build(parse("call_indirect-import"), imports);

        assertThrows(IllegalStateException.class, exporter::close);
        assertEquals(42L, exporter.export("call-self").apply()[0], "refusing changed nothing");

        importer.close();
        exporter.close();
    }

    @Test
    public void importsAreFreedByTheirFactoryOnceUnused() {
        var imports = ImportFactory.forNative(new JffiMachineFactoryProvider());
        var memory = imports.memory(new MemoryLimits(1, 1));
        var table = imports.table(tableType(), Value.REF_NULL_VALUE);
        var counter = imports.global(Value.i32(10), MutabilityType.Var);
        var instance = buildImportedMutables(memory, table, counter);
        instance.export("run").apply();

        assertThrows(IllegalStateException.class, imports::close);
        assertEquals(23130, memory.readInt(0), "refusing changed nothing");

        instance.close();
        imports.close();
        assertThrows(IllegalStateException.class, () -> memory.readInt(0));
    }

    @Test
    public void aFailedBuildDoesNotKeepItsImportsAlive() {
        var imports = ImportFactory.forNative(new JffiMachineFactoryProvider());
        var memory = imports.memory(new MemoryLimits(1, 1));
        var values =
                ImportValues.builder().addMemory(new ImportMemory("env", "memory", memory)).build();

        assertThrows(
                UninstantiableException.class,
                () -> build(parse("failing-start-imported-memory"), values));
        imports.close();
    }

    /** The spec keeps a failed build's functions in a table callable, until what they use closes. */
    @Test
    public void aFailedBuildInATableLastsUntilWhatItUsesCloses() {
        int before = NativeResourceCount.live();
        var imports = ImportFactory.forNative(new JffiMachineFactoryProvider());
        var memory = imports.memory(new MemoryLimits(1, 1));
        memory.writeI32(0, 7);
        var exporter = build(parse("call_indirect-export"), ImportValues.empty());
        var values =
                ImportValues.builder()
                        .addTable(
                                new ImportTable(
                                        "test",
                                        "shared-table",
                                        exporter.exports().table("shared-table")))
                        .addMemory(new ImportMemory("env", "memory", memory))
                        .build();

        assertThrows(
                UninstantiableException.class,
                () -> build(parse("failing-start-table-writer"), values));
        assertEquals(7L, exporter.export("call-other").apply()[0], "the failed build's function");

        imports.close();
        assertThrows(WasmEngineException.class, () -> exporter.export("call-other").apply());
        exporter.close();
        assertEquals(before, NativeResourceCount.live());
    }

    @Test
    public void aBuildThatFailsBeforeItsMachineFreesItsMemory() {
        int before = NativeResourceCount.live();
        var withMemory = parse("trap-stops-execution");
        var otherModulesCode = compile(parse("big-table"));

        assertThrows(WasmEngineException.class, () -> build(withMemory, otherModulesCode));
        assertEquals(before, NativeResourceCount.live());
    }

    private static WasmModule parse(String name) {
        return Parser.parse(CorpusResources.getResource("compiled/" + name + ".wat.wasm"));
    }

    private static NativeCode compile(WasmModule module) {
        return NativeCompiler.compile(RedlineTarget.detectHost().orElseThrow().triple(), module);
    }

    private static Instance build(WasmModule module, ImportValues imports) {
        return JffiNativeMachineFactory.builder(module)
                .withImportValues(imports)
                .withPrecompiledCode(compile(module))
                .build();
    }

    private static Instance build(WasmModule module, NativeCode code) {
        return JffiNativeMachineFactory.builder(module).withPrecompiledCode(code).build();
    }

    private static Instance buildImportedMutables(
            Memory memory, TableInstance table, GlobalInstance counter) {
        var imports =
                ImportValues.builder()
                        .addMemory(new ImportMemory("env", "memory", memory))
                        .addTable(new ImportTable("env", "table", table))
                        .addGlobal(new ImportGlobal("env", "counter", counter))
                        .build();
        return build(parse("imported-mutables"), imports);
    }

    private static Memory memory() {
        return JffiNativeMachineFactory.createMemory(new MemoryLimits(1, 1));
    }

    private static TableInstance table() {
        return JffiNativeMachineFactory.createImportTable(tableType(), Value.REF_NULL_VALUE);
    }

    private static GlobalInstance global() {
        return JffiNativeMachineFactory.createImportGlobal(10L, ValType.I32, MutabilityType.Var);
    }

    private static Table tableType() {
        return new Table(ValType.FuncRef, new TableLimits(2, 2));
    }
}
