package run.endive.redline.experimental.runner.jffi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.Instance;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/** A trap raised while the runner handles memory.init has to stop the guest there. */
public class MemoryInitTrapTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/memory-init-trap.wat.wasm"));
    private static final NativeCode CODE =
            NativeCompiler.compile(RedlineTarget.detectHost().orElseThrow().triple(), MODULE);

    @Test
    public void anOutOfBoundsInitStopsBeforeTheNextInstruction() {
        try (var instance = instance()) {
            assertThrows(
                    WasmRuntimeException.class,
                    () -> instance.export("initOutOfBoundsThenStore").apply());
            assertEquals(0L, instance.export("read").apply(100)[0]);
        }
    }

    @Test
    public void anEmptyInitPastTheEndOfADroppedSegmentTraps() {
        try (var instance = instance()) {
            assertThrows(
                    WasmRuntimeException.class,
                    () -> instance.export("dropThenInitPastEnd").apply());
        }
    }

    @Test
    public void anEmptyInitAtTheStartOfADroppedSegmentIsAllowed() {
        try (var instance = instance()) {
            instance.export("dropThenInitNothing").apply();
        }
    }

    private static Instance instance() {
        return JffiNativeMachineFactory.builder(MODULE).withPrecompiledCode(CODE).build();
    }
}
