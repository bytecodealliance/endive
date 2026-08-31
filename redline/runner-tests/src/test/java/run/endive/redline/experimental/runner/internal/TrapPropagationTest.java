package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;

/**
 * A trap has to abandon the caller, not just the frame it happened in. The spec
 * suite asserts on the exception a call ends with, which is reported correctly
 * either way, so it never noticed execution carrying on past the trap.
 */
public class TrapPropagationTest {

    @Test
    public void callerStopsWhenItsCalleeTraps() {
        var module = parse();
        try (var instance = build(module)) {
            assertThrows(
                    WasmEngineException.class, () -> instance.export("storeAfterTrap").apply());
            assertEquals(
                    0,
                    instance.memory().readInt(0),
                    "the store after the trapping call must never run");
        }
    }

    @Test
    public void matchesTheInterpreter() {
        var module = parse();
        var interpreter = run.endive.runtime.Instance.builder(module).build();
        assertThrows(WasmEngineException.class, () -> interpreter.export("loopAfterTrap").apply());
        int reference = interpreter.memory().readInt(4);

        try (var instance = build(module)) {
            assertThrows(WasmEngineException.class, () -> instance.export("loopAfterTrap").apply());
            assertEquals(reference, instance.memory().readInt(4), "must match the interpreter");
        }
    }

    private static WasmModule parse() {
        return Parser.parse(CorpusResources.getResource("compiled/trap-stops-execution.wat.wasm"));
    }

    private static run.endive.runtime.Instance build(WasmModule module) {
        return NativeMachineFactory.builder(module)
                .withCompilerFunction(
                        m ->
                                NativeCompiler.compileAll(
                                        RedlineTarget.detectHost().orElseThrow().triple(), m))
                .build();
    }
}
