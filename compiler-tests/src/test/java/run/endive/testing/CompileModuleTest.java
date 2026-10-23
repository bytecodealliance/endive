package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static run.endive.corpus.WatGenerator.methodTooLarge;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.CompiledModule;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.CodeSection;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

public class CompileModuleTest {

    // func_1 (function index 2, code index 1) is too large to compile and is interpreted.
    private static final WasmModule METHOD_TOO_LARGE =
            Parser.parse(Wat2Wasm.parse(methodTooLarge(20_000)));

    private static final HostFunction HOST_FUNC =
            new HostFunction(
                    "funcs",
                    "host_func",
                    FunctionType.of(List.of(ValType.I32), List.of(ValType.I32)),
                    (inst, args) -> new long[] {35});

    private static CompiledModule compileModule(WasmModule module) {
        return MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .compileModule();
    }

    private static Instance instantiate(CompiledModule compiled) {
        return Instance.builder(compiled)
                .withImportValues(ImportValues.builder().addFunction(HOST_FUNC).build())
                .withStart(false)
                .build();
    }

    private static void assertRetained(CodeSection code, Set<Integer> retained) {
        var bodies = code.functionBodies();
        for (int i = 0; i < bodies.length; i++) {
            assertEquals(retained.contains(i), bodies[i] != null, "function body " + i);
        }
    }

    @Test
    public void keepsOnlyInterpretedFunctionBodies() {
        var compiled = compileModule(METHOD_TOO_LARGE);

        var code = compiled.wasmModule().codeSection();
        assertEquals(3, code.functionBodyCount());
        assertRetained(code, Set.of(1));
        assertThrows(IllegalStateException.class, () -> code.getFunctionBody(0));
        // The module passed to the compiler is left unchanged.
        assertRetained(METHOD_TOO_LARGE.codeSection(), Set.of(0, 1, 2));

        var instance = instantiate(compiled);
        // Through the interpreted func_1, calling back into compiled code directly...
        assertEquals(35, instance.export("func_2").apply(0)[0]);
        // ...and through call_indirect.
        assertEquals(35, instance.export("func_2").apply(1)[0]);
    }

    @Test
    public void keepsFunctionsReachableThroughTailCalls() {
        // interpreted tail calls tail_called, which calls compiled
        var module =
                Parser.parse(
                        CorpusResources.getResource("compiled/tail_call_drop_bodies.wat.wasm"));
        var compiled =
                MachineFactoryCompiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.FAIL)
                        .withInterpretedFunctions(Set.of(0))
                        .compileModule();

        assertRetained(compiled.wasmModule().codeSection(), Set.of(0, 1));
        assertEquals(42, Instance.builder(compiled).build().export("interpreted").apply(41)[0]);
    }

    @Test
    public void compileKeepsTheModule() {
        var factory =
                MachineFactoryCompiler.builder(METHOD_TOO_LARGE)
                        .withInterpreterFallback(InterpreterFallback.SILENT)
                        .compile();

        assertSame(METHOD_TOO_LARGE, ((CompiledModule) factory).wasmModule());
    }

    @Test
    public void instanceRejectsDroppedBody() {
        var instance = instantiate(compileModule(METHOD_TOO_LARGE));

        var exception = assertThrows(IllegalStateException.class, () -> instance.function(1));
        assertTrue(exception.getMessage().contains("dropped"));
    }
}
