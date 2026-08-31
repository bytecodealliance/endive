package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.wasm.Parser;

/**
 * A table declared with an initialiser has to come up holding it. The spec suite
 * does not cover this: its tables are filled by element segments, which take a
 * different path.
 */
public class TableInitExprTest {

    @Test
    public void tableComesUpHoldingItsInitialiser() {
        var module = Parser.parse(CorpusResources.getResource("compiled/table-init-expr.wat.wasm"));

        try (var instance =
                NativeMachineFactory.builder(module)
                        .withCompilerFunction(
                                m ->
                                        NativeCompiler.compileAll(
                                                RedlineTarget.detectHost().orElseThrow().triple(),
                                                m))
                        .build()) {
            assertEquals(
                    42,
                    (int) instance.export("callInitialised").apply()[0],
                    "a slot filled only by the table initialiser must be callable");
        }
    }
}
