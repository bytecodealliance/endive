package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.wasm.Parser;
import run.endive.wasm.types.FunctionType;

/**
 * An exception from a host function has to abandon the module the same way a trap
 * does, otherwise the module keeps running on state the host has already rejected.
 */
public class HostThrowPropagationTest {

    private static final class Boom extends RuntimeException {
        Boom() {
            super("boom");
        }
    }

    @Test
    public void moduleStopsWhenAHostFunctionThrows() {
        var module =
                Parser.parse(
                        CorpusResources.getResource(
                                "compiled/host-throw-stops-execution.wat.wasm"));

        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "boom",
                                        FunctionType.of(java.util.List.of(), java.util.List.of()),
                                        (inst, args) -> {
                                            throw new Boom();
                                        }))
                        .build();

        try (var instance =
                NativeMachineFactory.builder(module)
                        .withImportValues(imports)
                        .withCompilerFunction(
                                m ->
                                        NativeCompiler.compileAll(
                                                RedlineTarget.detectHost().orElseThrow().triple(),
                                                m))
                        .build()) {
            assertThrows(Boom.class, () -> instance.export("callBoom").apply());
            assertEquals(
                    0,
                    instance.memory().readInt(0),
                    "the store after the throwing host call must never run");
        }
    }
}
