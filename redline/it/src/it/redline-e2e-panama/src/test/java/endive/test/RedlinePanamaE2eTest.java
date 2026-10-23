package endive.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.api.NativeMachineFactoryProvider;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.runtime.ImportGlobal;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportTable;
import run.endive.runtime.ImportValues;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

class RedlinePanamaE2eTest {

    @Test
    public void panamaProviderIsSelected() {
        var provider = NativeMachineFactoryProvider.discover();
        assertTrue(provider.isPresent(), "Should discover a native provider");
        assertEquals(100, provider.get().priority(), "Panama should win with priority 100");
    }

    /**
     * Without this, every other test here still passes when the native path silently
     * never engages, because the fallback is the build-time compiled bytecode and
     * produces identical results.
     */
    @Test
    public void builderActuallyUsesTheNativePath() {
        assumeTrue(
                RedlineTarget.detectHost().isPresent(),
                "Host is not one of the Redline target platforms");
        var provider = AddModule.nativeProvider();
        assertTrue(
                provider.isPresent(),
                "builder() must take the native path, not fall back to compiled bytecode");
        assertEquals(100, provider.get().priority(), "and it must be the Panama runner");
    }

    @Test
    public void nativeBuilderProducesCorrectResults() {
        try (var instance = AddModule.builder().build()) {
            var add = instance.export("add");
            assertArrayEquals(new long[] {3}, add.apply(1, 2));
            assertArrayEquals(new long[] {0}, add.apply(0, 0));
            assertEquals(
                    -1,
                    (int) add.apply(0, -1)[0],
                    "i32 add(0, -1) should be -1 when narrowed to int");
        }
    }

    /**
     * The whole point of the factory: build the imports, hand them to the module, and
     * read back through the very same objects what the module wrote to them. This is
     * the shape the documentation shows, and it has to hold whether this platform got
     * native code or fell back to the build-time compiled bytecode.
     */
    @Test
    public void theModuleSharesTheImportsItWasGiven() {
        try (var imports = ImportsModule.imports()) {
            var memory = imports.memory(new MemoryLimits(1, 1));
            var table =
                    imports.table(
                            new Table(ValType.FuncRef, new TableLimits(2, 2)),
                            Value.REF_NULL_VALUE);
            var counter = imports.global(Value.i32(10), MutabilityType.Var);

            var importValues =
                    ImportValues.builder()
                            .addMemory(new ImportMemory("env", "memory", memory))
                            .addTable(new ImportTable("env", "table", table))
                            .addGlobal(new ImportGlobal("env", "counter", counter))
                            .build();

            try (var instance = ImportsModule.builder().withImportValues(importValues).build()) {
                instance.export("run").apply();

                assertEquals(
                        11, counter.getValue(), "the caller's global must carry the increment");
                assertEquals(23130, memory.readInt(0), "the caller's memory must carry the write");
                assertNotEquals(
                        Value.REF_NULL_VALUE,
                        table.ref(0),
                        "the caller's table must carry the stored funcref");

                // and the other direction: what the caller writes, the module reads
                memory.writeI32(16, 21);
                instance.export("doubleAt").apply(16);
                assertEquals(
                        42,
                        memory.readInt(20),
                        "the module must read what the caller wrote to memory");
            }
        }
    }

    /**
     * imports() must agree with builder() about which backend is in play. Getting this
     * wrong is silent: the module still runs, and only the caller's view goes stale.
     */
    @Test
    public void importsFactoryMatchesTheBackendInUse() {
        assertEquals(
                AddModule.nativeProvider().isPresent(),
                AddModule.imports().isNative(),
                "imports() must build for the same backend builder() runs on");
    }

    @Test
    public void nativeCodeIsAvailable() {
        assumeTrue(
                RedlineTarget.detectHost().isPresent(),
                "Host is not one of the Redline target platforms, so no native code was"
                        + " cross-compiled for it");
        assertNotNull(
                AddModule.loadNativeCode(), "Native code should be available on this platform");
    }

    /** Native code is compiled at build time, so the compiler must not ship with the runner. */
    @Test
    public void compilerIsNotOnTheRuntimeClasspath() {
        for (String name :
                new String[] {
                    "run.endive.redline.experimental.bridge.internal.CraneliftBridge",
                    "run.endive.redline.experimental.compiler.internal.NativeCompiler"
                }) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
        }
    }

    @Test
    public void bothBuildersProduceSameResults() {
        try (var nativeInstance = AddModule.builder().build();
                var safeInstance = AddModule.safeBuilder().build()) {
            var nativeAdd = nativeInstance.export("add");
            var safeAdd = safeInstance.export("add");

            for (int a = -10; a <= 10; a++) {
                for (int b = -10; b <= 10; b++) {
                    assertEquals(
                            (int) safeAdd.apply(a, b)[0],
                            (int) nativeAdd.apply(a, b)[0],
                            "add(" + a + ", " + b + ") should match");
                }
            }
        }
    }
}
