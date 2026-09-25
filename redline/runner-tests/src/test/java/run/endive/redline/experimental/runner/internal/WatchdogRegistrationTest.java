package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.InterruptWatchdog;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.types.FunctionType;

/**
 * Interrupting a call means raising a flag that compiled code polls, and the JVM offers no
 * callback on {@link Thread#interrupt()} to raise it from. The runners used to answer that
 * with a fresh thread per call, which a host function calling back into an export paid
 * again for every nested level.
 *
 * <p>These two tests pin the replacement from both ends: one registration per outermost
 * call however deep the nesting goes, and no threads started as a result of calling.
 */
public class WatchdogRegistrationTest {

    /** Nesting per call. Well clear of the reentrant stack guard, which fires near 115. */
    private static final int DEPTH = 20;

    private static final int ROUNDS = 20;

    @Test
    public void nestingAddsNoRegistrations() {
        int[] deepest = {0};
        withReentrantInstance(
                instance -> {
                    instance.export("recurse").apply();
                },
                level -> deepest[0] = Math.max(deepest[0], InterruptWatchdog.activeCount()));

        assertEquals(
                1,
                deepest[0],
                "a nested call runs on the same thread inside the same watched window, so it"
                        + " must reuse the outermost call's registration");
    }

    @Test
    public void callingStartsNoThreads() {
        var threads = ManagementFactory.getThreadMXBean();
        long[] started = {0};

        withReentrantInstance(
                instance -> {
                    // One call first, so the shared poller is already running by the time
                    // the counter is read.
                    instance.export("recurse").apply();

                    long before = threads.getTotalStartedThreadCount();
                    for (int i = 0; i < ROUNDS; i++) {
                        instance.export("recurse").apply();
                    }
                    started[0] = threads.getTotalStartedThreadCount() - before;
                },
                level -> {});

        // Generous, because the JIT may start compiler threads while this runs. The
        // behaviour being excluded started one thread per call, so ROUNDS * (DEPTH + 1).
        assertTrue(
                started[0] < ROUNDS,
                "calling must not start a thread per call, but "
                        + started[0]
                        + " threads started across "
                        + (ROUNDS * (DEPTH + 1))
                        + " calls");
    }

    private static void withReentrantInstance(
            java.util.function.Consumer<Instance> body, IntConsumer atEachLevel) {
        var module =
                Parser.parse(CorpusResources.getResource("compiled/reentrant-recursion.wat.wasm"));

        int[] depth = {0};
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "reenter",
                                        FunctionType.of(List.of(), List.of()),
                                        (Instance inst, long... args) -> {
                                            atEachLevel.accept(depth[0]);
                                            if (depth[0]++ < DEPTH) {
                                                inst.export("recurse").apply();
                                            }
                                            depth[0] = 0;
                                            return null;
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
            body.accept(instance);
        }
    }
}
