import io.roastedroot.quickjs4j.core.Builtins;
import io.roastedroot.quickjs4j.core.Engine;
import io.roastedroot.quickjs4j.core.Runner;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Measures QuickJS throughput under quickjs4j, to show the effect of splitting oversized Wasm
 * functions into JIT-eligible JVM methods.
 *
 * <p>Everything about the two runs this is compared across is identical -- same quickjs4j source,
 * same JVM, same script -- except which build of the endive compiler produced the precompiled
 * bytecode in the quickjs4j jar on the classpath. See the README next to this file.
 *
 * <p>Results are reported per window rather than as a single number because the effect is a
 * JIT-compilation effect. A method past HotSpot's HugeMethodLimit is never compiled, so the
 * baseline is expected to be flat: slow on the first window and equally slow on the last. The
 * patched build should start comparably and then pull away as HotSpot compiles the chunks. A single
 * averaged figure would hide exactly the shape that shows the cause.
 *
 * <p>Usage: {@code java -cp ... JitBenchmark <label> <windows> <itersPerWindow> <out.csv>}
 */
public final class JitBenchmark {

    /**
     * Deliberately ordinary JavaScript: recursion, loop arithmetic, array and string work. The
     * point is to keep QuickJS's own interpreter loop (JS_CallInternal, the function this change
     * affects most) busy with work a real script would do, not to construct something tuned to
     * favour the change.
     */
    private static final String WORKLOAD =
            "function fib(n){ return n < 2 ? n : fib(n-1) + fib(n-2); }\n"
                    + "var acc = 0;\n"
                    + "for (var i = 0; i < 8; i++) { acc += fib(17); }\n"
                    + "var s = 0;\n"
                    + "for (var j = 0; j < 40000; j++) { s = (s + j * 3) % 100000; }\n"
                    + "var arr = [];\n"
                    + "for (var k = 0; k < 3000; k++) { arr.push((k * 7) % 251); }\n"
                    + "arr.sort(function(a,b){ return a - b; });\n"
                    + "var t = arr.filter(function(x){ return x % 2 === 0; })\n"
                    + "           .reduce(function(a,b){ return a + b; }, 0);\n"
                    + "var str = '';\n"
                    + "for (var m = 0; m < 2000; m++) { str += String.fromCharCode(65 + (m % 26)); }\n"
                    + "var h = 0;\n"
                    + "for (var n2 = 0; n2 < str.length; n2++) { h = (h * 31 + str.charCodeAt(n2)) | 0; }\n"
                    + "from_java.sink(((acc + s + t + h) | 0));\n";

    public static void main(String[] args) throws IOException {
        String label = args.length > 0 ? args[0] : "run";
        int windows = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        int perWindow = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        Path csv = args.length > 3 ? Path.of(args[3]) : null;

        List<Integer> results = new ArrayList<>();
        var builtins = Builtins.builder("from_java").addIntToVoid("sink", results::add).build();

        // One untimed pass: a perf number is meaningless if the two builds disagree
        // about what the script computes, so establish the checksum first.
        runOnce(builtins);
        int checksum = results.get(0);
        System.out.println("label:    " + label);
        System.out.println("checksum: " + checksum);

        List<Double> perWindowMs = new ArrayList<>();
        for (int w = 0; w < windows; w++) {
            long started = System.nanoTime();
            for (int i = 0; i < perWindow; i++) {
                runOnce(builtins);
            }
            double msPerIter = (System.nanoTime() - started) / 1e6 / perWindow;
            perWindowMs.add(msPerIter);
            System.out.printf("window %2d: %8.1f ms/iter%n", w, msPerIter);
        }

        double best = perWindowMs.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double first = perWindowMs.get(0);
        // steady state = mean of the last third, after any warm-up has settled
        int tailFrom = Math.max(0, perWindowMs.size() - Math.max(1, perWindowMs.size() / 3));
        double steady =
                perWindowMs.subList(tailFrom, perWindowMs.size()).stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElseThrow();

        System.out.printf("first=%.1f steady=%.1f best=%.1f ms/iter%n", first, steady, best);

        // Every iteration must have produced the same value, or the timings are
        // comparing two different computations.
        Set<Integer> distinct = new LinkedHashSet<>(results);
        boolean consistent = distinct.size() == 1;
        System.out.println(
                "consistent: " + consistent + " (" + results.size() + " iterations)");
        if (!consistent) {
            System.out.println("FAILED: iterations disagreed: " + distinct);
            System.exit(1);
        }

        if (csv != null) {
            try (PrintWriter out =
                    new PrintWriter(
                            Files.newBufferedWriter(csv, StandardCharsets.UTF_8))) {
                out.println("label,window,ms_per_iter,checksum");
                for (int w = 0; w < perWindowMs.size(); w++) {
                    out.printf("%s,%d,%.3f,%d%n", label, w, perWindowMs.get(w), checksum);
                }
            }
            System.out.println("wrote " + csv.toAbsolutePath());
        }
    }

    /**
     * One full engine lifecycle per iteration, which is what a host embedding QuickJS actually
     * does: it exercises compilation (the parser functions) as well as execution.
     */
    private static void runOnce(Builtins builtins) {
        var engine = Engine.builder().addBuiltins(builtins).build();
        var runner = Runner.builder().withEngine(engine).build();
        try {
            runner.compileAndExec(WORKLOAD);
        } finally {
            runner.close();
        }
    }
}
