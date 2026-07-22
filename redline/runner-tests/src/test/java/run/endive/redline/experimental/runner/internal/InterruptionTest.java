package run.endive.redline.experimental.runner.internal;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;

public class InterruptionTest {

    @Test
    public void shouldInterruptLoopViaThread() throws InterruptedException {
        try (var instance = buildInstance("compiled/infinite-loop.c.wasm")) {
            var function = instance.export("run");
            assertThreadInterruption(function::apply);
        }
    }

    @Test
    public void shouldInterruptCallViaThread() throws InterruptedException {
        try (var instance = buildInstance("compiled/power.c.wasm")) {
            var function = instance.export("run");
            assertThreadInterruption(() -> function.apply(100));
        }
    }

    private static void assertThreadInterruption(Runnable function) throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread =
                new Thread(
                        () -> {
                            var e = assertThrows(WasmEngineException.class, function::run);
                            assertEquals("interrupted", e.getMessage());
                            interrupted.set(true);
                        });
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(100);

        thread.interrupt();
        SECONDS.timedJoin(thread, 10);
        assertTrue(interrupted.get());
    }

    private static Instance buildInstance(String resource) {
        var module = Parser.parse(CorpusResources.getResource(resource));
        return NativeMachineFactory.builder(module)
                .withCompilerFunction(
                        m ->
                                NativeCompiler.compileAll(
                                        RedlineTarget.detectHost().orElseThrow().triple(), m))
                .build();
    }
}
