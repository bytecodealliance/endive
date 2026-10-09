package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.MemoryLimits;

/** Compiled code sees growth by another instance sharing the memory, or by a host import. */
public class GuestSeesGrowthTest {

    private static final int PAGE = 65536;

    private static final WasmModule SHARED = parse("shared-memory-grow");
    private static final NativeCode SHARED_CODE = compile(SHARED);
    private static final WasmModule HOST_GROW = parse("host-grow");
    private static final NativeCode HOST_GROW_CODE = compile(HOST_GROW);

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    @AfterAll
    public static void shutdown() {
        POOL.shutdownNow();
    }

    @Test
    public void aRunningInstanceReadsThePageAnotherInstanceGrew() throws Exception {
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2, true));
        try (var waiter = shared(memory);
                var grower = shared(memory)) {
            var read = POOL.submit(() -> waiter.export("waitThenRead").apply(0, PAGE + 16)[0]);
            Thread.sleep(50);
            grower.export("growAndPublish").apply(0, PAGE + 16, 42);
            assertEquals(42L, read.get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aRunningInstanceSeesTheSizeAnotherInstanceGrew() throws Exception {
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2, true));
        try (var waiter = shared(memory);
                var grower = shared(memory)) {
            var size = POOL.submit(() -> waiter.export("waitThenSize").apply(0)[0]);
            Thread.sleep(50);
            grower.export("growAndPublish").apply(0, PAGE + 16, 42);
            assertEquals(2L, size.get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    public void theGuestReadsThePageAHostImportGrew() {
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        try (var instance = hostGrow(memory, 77)) {
            assertEquals(77L, instance.export("growThenRead").apply(PAGE + 8)[0]);
        }
    }

    @Test
    public void theGuestSeesTheSizeAHostImportGrew() {
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        try (var instance = hostGrow(memory, 77)) {
            assertEquals(2L, instance.export("growThenSize").apply()[0]);
        }
    }

    private static Instance shared(Memory memory) {
        return NativeMachineFactory.builder(SHARED)
                .withPrecompiledCode(SHARED_CODE)
                .withImportValues(
                        ImportValues.builder()
                                .addMemory(new ImportMemory("env", "memory", memory))
                                .build())
                .build();
    }

    private static Instance hostGrow(Memory memory, int value) {
        var grow =
                new HostFunction(
                        "env",
                        "grow",
                        FunctionType.of(List.of(), List.of()),
                        (instance, args) -> {
                            memory.grow(1);
                            memory.writeI32(PAGE + 8, value);
                            return null;
                        });
        return NativeMachineFactory.builder(HOST_GROW)
                .withPrecompiledCode(HOST_GROW_CODE)
                .withImportValues(
                        ImportValues.builder()
                                .addMemory(new ImportMemory("env", "memory", memory))
                                .addFunction(grow)
                                .build())
                .build();
    }

    private static WasmModule parse(String name) {
        return Parser.parse(CorpusResources.getResource("compiled/" + name + ".wat.wasm"));
    }

    private static NativeCode compile(WasmModule module) {
        return NativeCompiler.compile(RedlineTarget.detectHost().orElseThrow().triple(), module);
    }
}
