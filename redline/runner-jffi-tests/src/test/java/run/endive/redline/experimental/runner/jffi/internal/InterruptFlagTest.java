package run.endive.redline.experimental.runner.jffi.internal;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.testing.NativeInstanceBuilder;
import run.endive.wasm.Parser;
import run.endive.wasm.types.FunctionType;

/** An interrupt the host handled during a call must not stop a later call. */
public class InterruptFlagTest {

    @AfterEach
    public void clearInterruptStatus() {
        // Keeps a failure from leaking an interrupt into the rest of the suite.
        Thread.interrupted();
    }

    @Test
    public void anInterruptHandledByTheHostDoesNotStopTheNextCall() {
        var module =
                Parser.parse(CorpusResources.getResource("compiled/interrupt-midcall.wat.wasm"));
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "raiseFlag",
                                        FunctionType.of(List.of(), List.of()),
                                        (inst, args) -> {
                                            interruptAndHandle();
                                            return null;
                                        }))
                        .build();

        try (var instance =
                NativeInstanceBuilder.builder(module).withImportValues(imports).build()) {
            // nothing polls after the host function, so this returns normally
            instance.export("callHost").apply();

            assertEquals(
                    42,
                    (int) instance.export("answer").apply()[0],
                    "an interrupt handled during the previous call must not stop this one");
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    public void anInterruptHandledInANestedCallDoesNotStopTheOuterCall() {
        var module =
                Parser.parse(CorpusResources.getResource("compiled/reentrant-interrupt.wat.wasm"));
        var noParams = FunctionType.of(List.of(), List.of());
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "reenter",
                                        noParams,
                                        (inst, args) -> {
                                            inst.export("raise").apply();
                                            return null;
                                        }))
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "raiseFlag",
                                        noParams,
                                        (inst, args) -> {
                                            interruptAndHandle();
                                            return null;
                                        }))
                        .addFunction(
                                new HostFunction("host", "tick", noParams, (inst, args) -> null))
                        .build();

        try (var instance =
                NativeInstanceBuilder.builder(module).withImportValues(imports).build()) {
            assertEquals(
                    1000,
                    (int) instance.export("run").apply()[0],
                    "an interrupt handled in the nested call must not stop the outer one");
        }
    }

    // interrupts this thread, gives the watchdog time to see it, then handles it as a host would
    private static void interruptAndHandle() {
        Thread.currentThread().interrupt();
        long end = System.nanoTime() + MILLISECONDS.toNanos(500);
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
        Thread.interrupted();
    }
}
