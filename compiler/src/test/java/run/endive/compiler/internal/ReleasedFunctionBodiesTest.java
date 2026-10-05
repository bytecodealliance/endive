package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.CompiledModule;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;

public class ReleasedFunctionBodiesTest {

    @Test
    public void compiledFunctionsRunWithoutTheirInstructions() {
        WasmModule module = Parser.parse(CorpusResources.getResource("compiled/power.c.wasm"));
        var factory = MachineFactoryCompiler.builder(module).withReleasedFunctionBodies().compile();
        WasmModule released = ((CompiledModule) factory).wasmModule();

        for (var body : released.codeSection().functionBodies()) {
            assertTrue(body.instructions().isEmpty());
        }
        var instance = Instance.builder(released).withMachineFactory(factory).build();
        assertEquals(16, instance.export("run").apply(4)[0]);
    }

    @Test
    public void interpretedFunctionsKeepTheirInstructions() {
        WasmModule module = Parser.parse(CorpusResources.getResource("compiled/power.c.wasm"));
        int run = exportedFunction(module, "run");
        int imports = module.importSection().count(ExternalType.FUNCTION);
        var factory =
                MachineFactoryCompiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.FAIL)
                        .withInterpretedFunctions(Set.of(run))
                        .withReleasedFunctionBodies()
                        .compile();
        WasmModule released = ((CompiledModule) factory).wasmModule();

        assertFalse(released.codeSection().getFunctionBody(run - imports).instructions().isEmpty());
        var instance = Instance.builder(released).withMachineFactory(factory).build();
        assertEquals(16, instance.export("run").apply(4)[0]);
    }

    private static int exportedFunction(WasmModule module, String name) {
        var exports = module.exportSection();
        for (int i = 0; i < exports.exportCount(); i++) {
            if (exports.getExport(i).name().equals(name)) {
                return (int) exports.getExport(i).index();
            }
        }
        throw new IllegalArgumentException("no export " + name);
    }
}
