package run.endive.redline.experimental.runner.jffi.internal;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.FunctionType;

/**
 * Recursion that goes back through the host re-enters the machine from the top
 * every time. The stack guard has to stay anchored where the outermost call
 * started: re-anchoring per call moves the limit deeper on every level, so it
 * never fires and the JVM raises StackOverflowError instead. That is an Error,
 * which callers guarding against runaway modules do not catch.
 */
public class ReentrantStackGuardTest {

    /** Only a backstop: the guard is expected to fire long before this. */
    private static final int CAP = 20_000;

    @Test
    public void theGuardStillFiresWhenRecursionGoesThroughTheHost() {
        var thrown = assertThrows(Throwable.class, () -> recurseThroughHost(true));
        assertInstanceOf(
                WasmEngineException.class,
                thrown,
                "must trap rather than let the JVM raise StackOverflowError");
        assertTrue(
                String.valueOf(thrown.getMessage()).contains("call stack exhausted"),
                "expected a call stack exhausted trap, got: " + thrown.getMessage());
    }

    @Test
    public void matchesTheInterpreter() {
        var reference = assertThrows(Throwable.class, () -> recurseThroughHost(false));
        assertInstanceOf(WasmEngineException.class, reference);

        var actual = assertThrows(Throwable.class, () -> recurseThroughHost(true));
        assertInstanceOf(
                reference.getClass(),
                actual,
                "redline must end this the same way the interpreter does");
    }

    private static void recurseThroughHost(boolean native_) {
        var module =
                Parser.parse(CorpusResources.getResource("compiled/reentrant-recursion.wat.wasm"));

        int[] depth = {0};
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "reenter",
                                        FunctionType.of(java.util.List.of(), java.util.List.of()),
                                        (inst, args) -> {
                                            if (depth[0]++ < CAP) {
                                                inst.export("recurse").apply();
                                            }
                                            return null;
                                        }))
                        .build();

        if (!native_) {
            run.endive.runtime.Instance.builder(module)
                    .withImportValues(imports)
                    .build()
                    .export("recurse")
                    .apply();
            return;
        }

        try (var instance =
                JffiNativeMachineFactory.builder(module)
                        .withImportValues(imports)
                        .withCompilerFunction(
                                m ->
                                        NativeCompiler.compileAll(
                                                RedlineTarget.detectHost().orElseThrow().triple(),
                                                m))
                        .build()) {
            instance.export("recurse").apply();
        }
    }
}
