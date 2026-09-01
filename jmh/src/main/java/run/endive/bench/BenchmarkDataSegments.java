package run.endive.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Measures the cost of reading data segments: once per instance for an active segment, and once per
 * {@code memory.init} for a passive one.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
public class BenchmarkDataSegments {

    // Bytes written by each memory.init, much smaller than the segment itself
    private static final int INIT_SIZE = 64;

    @Param({"1024", "1048576"})
    private int segmentSize;

    private WasmModule activeModule;
    private ExportFunction memoryInit;

    @Setup
    public void setup() {
        var data = "\\00".repeat(segmentSize);
        var pages = (segmentSize / 65536) + 1;

        activeModule =
                Parser.parse(
                        Wat2Wasm.parse(
                                "(module (memory "
                                        + pages
                                        + ") (data (i32.const 0) \""
                                        + data
                                        + "\"))"));

        var passiveModule =
                Parser.parse(
                        Wat2Wasm.parse(
                                "(module (memory "
                                        + pages
                                        + ") (data $seg \""
                                        + data
                                        + "\")"
                                        + " (func (export \"init\") (param i32 i32 i32)"
                                        + " local.get 0 local.get 1 local.get 2"
                                        + " memory.init $seg))"));
        memoryInit = Instance.builder(passiveModule).build().export("init");
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void benchmarkInstantiate(Blackhole bh) {
        bh.consume(Instance.builder(activeModule).build());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void benchmarkMemoryInit(Blackhole bh) {
        bh.consume(memoryInit.apply(0, 0, INIT_SIZE));
    }
}
