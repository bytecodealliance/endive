package run.endive.bench;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/**
 * Crossing the host boundary, which is where the native backend used to lose to the
 * bytecode one: every call into native code started a watchdog thread, and a host function
 * calling back into an export paid it again for the nested call.
 *
 * <p>Run at one and at eight threads, because thread creation serialises — the gap widened
 * with thread count rather than narrowing.
 *
 * <p>Compilation happens in setup, so these measure calling, not compiling.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
public class BenchmarkRedlineHostCalls {

    private static final File ROUNDTRIP =
            new File("wasm-corpus/src/main/resources/compiled/host-import-roundtrip.wat.wasm");

    private static final File REENTRANT =
            new File("wasm-corpus/src/main/resources/compiled/reentrant-recursion.wat.wasm");

    /** Nested levels per call, well clear of the reentrant stack guard. */
    private static final int DEPTH = 8;

    private Instance bytecodeRoundtrip;
    private Instance nativeRoundtrip;
    private Instance bytecodeReentrant;
    private Instance nativeReentrant;

    private ExportFunction bytecodeHostCall;
    private ExportFunction nativeHostCall;
    private ExportFunction bytecodeNestedCall;
    private ExportFunction nativeNestedCall;

    @Setup
    public void setup() {
        bytecodeRoundtrip =
                Instance.builder(Parser.parse(ROUNDTRIP))
                        .withImportValues(roundtripImports())
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        bytecodeHostCall = bytecodeRoundtrip.export("callTakeI32");

        nativeRoundtrip =
                JffiNativeMachineFactory.builder(Parser.parse(ROUNDTRIP))
                        .withImportValues(roundtripImports())
                        .withCompilerFunction(BenchmarkRedlineHostCalls::compileNative)
                        .build();
        nativeHostCall = nativeRoundtrip.export("callTakeI32");

        bytecodeReentrant =
                Instance.builder(Parser.parse(REENTRANT))
                        .withImportValues(reentrantImports())
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        bytecodeNestedCall = bytecodeReentrant.export("recurse");

        nativeReentrant =
                JffiNativeMachineFactory.builder(Parser.parse(REENTRANT))
                        .withImportValues(reentrantImports())
                        .withCompilerFunction(BenchmarkRedlineHostCalls::compileNative)
                        .build();
        nativeNestedCall = nativeReentrant.export("recurse");
    }

    @TearDown
    public void tearDown() {
        nativeRoundtrip.close();
        nativeReentrant.close();
        bytecodeRoundtrip.close();
        bytecodeReentrant.close();
    }

    private static byte[][] compileNative(WasmModule module) {
        return NativeCompiler.compileAll(RedlineTarget.detectHost().orElseThrow().triple(), module);
    }

    private static ImportValues roundtripImports() {
        return ImportValues.builder()
                .addFunction(
                        new HostFunction(
                                "host",
                                "retF32",
                                FunctionType.of(List.of(), List.of(ValType.F32)),
                                (inst, args) -> new long[] {Float.floatToRawIntBits(1.5f)}))
                .addFunction(
                        new HostFunction(
                                "host",
                                "retF64",
                                FunctionType.of(List.of(), List.of(ValType.F64)),
                                (inst, args) -> new long[] {Double.doubleToRawLongBits(1.5)}))
                .addFunction(
                        new HostFunction(
                                "host",
                                "takeI32",
                                FunctionType.of(List.of(ValType.I32), List.of(ValType.I32)),
                                (inst, args) -> new long[] {args[0]}))
                .addFunction(
                        new HostFunction(
                                "host",
                                "retPair",
                                FunctionType.of(List.of(), List.of(ValType.I32, ValType.I32)),
                                (inst, args) -> new long[] {10, 20}))
                .build();
    }

    private static ImportValues reentrantImports() {
        int[] depth = {0};
        return ImportValues.builder()
                .addFunction(
                        new HostFunction(
                                "host",
                                "reenter",
                                FunctionType.of(List.of(), List.of()),
                                (inst, args) -> {
                                    if (depth[0]++ < DEPTH) {
                                        inst.export("recurse").apply();
                                    }
                                    depth[0] = 0;
                                    return null;
                                }))
                .build();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(1)
    public void hostCallBytecode(Blackhole bh) {
        bh.consume(bytecodeHostCall.apply());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(1)
    public void hostCallNative(Blackhole bh) {
        bh.consume(nativeHostCall.apply());
    }

    /**
     * One instance per thread, because a single native instance is not safe to call from
     * several threads at once: {@code callDepth} is an unsynchronised field, so concurrent
     * callers stop the stack guard from re-anchoring and it fires spuriously. That predates
     * the watchdog work and is not what this benchmark is measuring.
     *
     * <p>Per-thread instances are also the shape the thread-creation cost showed up in:
     * starting a thread per call serialises on the OS, so the gap widened with thread count.
     */
    @State(Scope.Thread)
    public static class PerThreadNative {

        Instance instance;
        ExportFunction hostCall;

        @Setup
        public void setup() {
            instance =
                    JffiNativeMachineFactory.builder(Parser.parse(ROUNDTRIP))
                            .withImportValues(roundtripImports())
                            .withCompilerFunction(BenchmarkRedlineHostCalls::compileNative)
                            .build();
            hostCall = instance.export("callTakeI32");
        }

        @TearDown
        public void tearDown() {
            instance.close();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(8)
    public void hostCallNativeEightThreads(PerThreadNative state, Blackhole bh) {
        bh.consume(state.hostCall.apply());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(1)
    public void nestedCallBytecode(Blackhole bh) {
        bh.consume(bytecodeNestedCall.apply());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(1)
    public void nestedCallNative(Blackhole bh) {
        bh.consume(nativeNestedCall.apply());
    }
}
