package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Everything a machine releases on close is an off-heap free, so closing twice
 * has to be a no-op rather than a double free, and a memory the instance only
 * borrowed has to survive it.
 */
public class LifecycleTest {

    @Test
    public void closingTwiceIsSafe() {
        var instance = build(parse());
        instance.close();
        instance.close();
    }

    @Test
    public void aMemoryTheModuleDefinesStillWorksBeforeClose() {
        try (var instance = build(parse())) {
            instance.memory().writeI32(0, 0x5A5A5A5A);
            assertEquals(0x5A5A5A5A, instance.memory().readInt(0));
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
