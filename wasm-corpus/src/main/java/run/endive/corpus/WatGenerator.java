package run.endive.corpus;

import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

public final class WatGenerator {

    private WatGenerator() {
        // Prevent instantiation
    }

    public static String bigWat(int funcCount, int funcSize) {
        ArrayList<Integer> functions = new ArrayList<>();
        ArrayList<Integer> instructions = new ArrayList<>();
        for (int i = 0; i < funcCount; i++) {
            functions.add(i + 1);
        }
        for (int i = 0; i < funcSize; i++) {
            instructions.add(i + 1);
        }

        return render(
                "/run/endive/corpus/big.wat",
                Map.of(
                        "functions", functions,
                        "instructions", instructions));
    }

    public static String methodTooLargeWithMemoryImport(int funcSize) {
        ArrayList<Integer> instructions = new ArrayList<>();
        for (int i = 0; i < funcSize; i++) {
            instructions.add(i + 1);
        }

        return render(
                "/run/endive/corpus/method_too_large_with_memory_import.wat",
                Map.of("instructions", instructions));
    }

    public static String methodTooLarge(int funcSize) {
        ArrayList<Integer> instructions = new ArrayList<>();
        for (int i = 0; i < funcSize; i++) {
            instructions.add(i + 1);
        }

        return render(
                "/run/endive/corpus/method_too_large.wat", Map.of("instructions", instructions));
    }

    public static String callIndirectTargets(int funcCount) {
        ArrayList<Integer> functions = new ArrayList<>();
        for (int i = 0; i < funcCount; i++) {
            functions.add(i);
        }

        return render(
                "/run/endive/corpus/call_indirect_targets.wat", Map.of("functions", functions));
    }

    public static String wideResults(int i32Count, int i64Count) {
        return render(
                "/run/endive/corpus/wide_results.wat",
                Map.of(
                        "i32s", Collections.nCopies(i32Count, 42),
                        "i64s", range(i64Count, i -> i - 64)));
    }

    public static String wideResultsMixed(int i64Count) {
        return render(
                "/run/endive/corpus/wide_results_mixed.wat",
                Map.of("i64s", range(i64Count, i -> Long.MIN_VALUE + i)));
    }

    public static String wideResultsRef(int i64Count) {
        return render(
                "/run/endive/corpus/wide_results_ref.wat",
                Map.of("i64s", range(i64Count, i -> i * 3L - 100)));
    }

    public static String wideResultsSplit(int i64Count, boolean inLoop) {
        return render(
                "/run/endive/corpus/wide_results_split.wat",
                Map.of(
                        "i64s", range(i64Count, i -> i),
                        "blocks", range(8, i -> i),
                        "statements", range(200, i -> i),
                        "inLoop", inLoop));
    }

    private static List<Long> range(int count, LongUnaryOperator value) {
        var values = new ArrayList<Long>();
        for (int i = 0; i < count; i++) {
            values.add(value.applyAsLong(i));
        }
        return values;
    }

    private static String render(String template, Map<String, Object> map) {
        VelocityEngine velocityEngine = new VelocityEngine();
        velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        velocityEngine.setProperty(
                "classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        velocityEngine.init();

        Template t = velocityEngine.getTemplate(template);

        VelocityContext context = new VelocityContext();
        for (var entry : map.entrySet()) {
            context.put(entry.getKey(), entry.getValue());
        }

        StringWriter writer = new StringWriter();
        t.merge(context, writer);
        writer.flush();
        return writer.toString();
    }

    public static void main(String[] args) {
        var remaining = new ArrayDeque<>(List.of(args));
        int funcCount = 50_000;
        int funcSize = 0;
        if (!remaining.isEmpty()) {
            funcCount = Integer.parseInt(remaining.removeFirst());
        }
        if (!remaining.isEmpty()) {
            funcSize = Integer.parseInt(remaining.removeFirst());
        }
        System.out.println(bigWat(funcCount, funcSize));
    }
}
