package run.endive.bench;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.ExternalProfiler;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.TextResult;

/**
 * Reports the C2 inlining decisions taken in Endive generated code, read from HotSpot's {@code
 * -XX:+LogCompilation} output.
 *
 * <p>Run a benchmark with {@code -prof run.endive.bench.InliningProfiler}, or pass to {@link
 * #main} a log written with {@code -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation
 * -XX:LogFile=<file>}.
 */
public final class InliningProfiler implements ExternalProfiler {

    private static final Pattern GENERATED =
            Pattern.compile(
                    ".*Machine(Shaded|FuncGroup_\\d+|Dispatch_\\d+|MachineCall|Indirect_\\d+_\\d+"
                            + "|CallIndirectBridge_\\d+)?");
    private static final Pattern JDK = Pattern.compile("(java|jdk|sun|com\\.sun)\\..*");
    // C2 DesiredMethodLimit
    private static final int INLINING_BUDGET = 8000;

    private final Path logDir = Path.of(System.getProperty("java.io.tmpdir"));

    @Override
    public String getDescription() {
        return "C2 inlining decisions in Endive generated code";
    }

    @Override
    public Collection<String> addJVMInvokeOptions(BenchmarkParams params) {
        return List.of();
    }

    @Override
    public Collection<String> addJVMOptions(BenchmarkParams params) {
        return List.of(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation",
                "-XX:LogFile=" + logDir.resolve("endive-inlining-%p.log"));
    }

    @Override
    public void beforeTrial(BenchmarkParams params) {}

    @Override
    public Collection<? extends Result> afterTrial(
            BenchmarkResult result, long pid, File stdOut, File stdErr) {
        var log = logDir.resolve("endive-inlining-pid" + pid + ".log");
        try {
            var report = report(log);
            Files.delete(log);
            return List.of(new TextResult(report, "inlining"));
        } catch (IOException | XMLStreamException e) {
            throw new IllegalStateException("Cannot read " + log, e);
        }
    }

    @Override
    public boolean allowPrintOut() {
        return true;
    }

    @Override
    public boolean allowPrintErr() {
        return true;
    }

    public static void main(String[] args) throws IOException, XMLStreamException {
        System.out.println(report(Path.of(args[0])));
    }

    private static final class Callee {
        final String name;
        final int bytes;
        int sites;
        int inlined;
        final Map<String, Integer> failures = new HashMap<>();

        Callee(String name, int bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    static String report(Path log) throws IOException, XMLStreamException {
        var callees = new TreeMap<String, Callee>();
        var nmsizes = new HashMap<String, Integer>();
        int compilations = 0;
        int overBudget = 0;

        // ids of klass and method elements are scoped to their task
        var klasses = new HashMap<String, String>();
        var methods = new HashMap<String, String[]>();
        boolean c2 = false;
        boolean generated = false;
        String root = null;
        int rootBytes = 0;
        String call = null;

        try (InputStream in = Files.newInputStream(log)) {
            XMLStreamReader xml = XMLInputFactory.newInstance().createXMLStreamReader(in);
            while (xml.hasNext()) {
                if (xml.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (xml.getLocalName()) {
                    case "task":
                        // C1 tasks carry a tier level, C2 tasks don't
                        c2 = attr(xml, "level") == null;
                        var method = attr(xml, "method").split(" ", 3);
                        rootBytes = Integer.parseInt(attr(xml, "bytes"));
                        root = method[0] + "::" + method[1] + "#" + rootBytes;
                        generated = c2 && GENERATED.matcher(method[0]).matches();
                        klasses.clear();
                        methods.clear();
                        break;
                    case "klass":
                        klasses.put(attr(xml, "id"), attr(xml, "name"));
                        break;
                    case "method":
                        methods.put(
                                attr(xml, "id"),
                                new String[] {
                                    attr(xml, "holder"), attr(xml, "name"), attr(xml, "bytes")
                                });
                        break;
                    case "call":
                        // a virtual call is followed by a second call for the devirtualized target
                        call = attr(xml, "method");
                        break;
                    case "inline_success":
                    case "inline_fail":
                        var target = generated ? methods.get(call) : null;
                        var holder = target == null ? null : klasses.get(target[0]);
                        if (holder == null || JDK.matcher(holder).matches()) {
                            break;
                        }
                        var callee =
                                callees.computeIfAbsent(
                                        holder + "::" + target[1] + "#" + target[2],
                                        k ->
                                                new Callee(
                                                        holder + "::" + target[1],
                                                        Integer.parseInt(target[2])));
                        callee.sites++;
                        if (xml.getLocalName().equals("inline_success")) {
                            callee.inlined++;
                        } else {
                            callee.failures.merge(attr(xml, "reason"), 1, Integer::sum);
                        }
                        break;
                    case "task_done":
                        if (c2 && attr(xml, "nmsize") != null) {
                            nmsizes.merge(root, Integer.parseInt(attr(xml, "nmsize")), Math::max);
                        }
                        if (generated) {
                            compilations++;
                            var inlined = attr(xml, "inlined_bytes");
                            if (rootBytes + (inlined == null ? 0 : Integer.parseInt(inlined))
                                    >= INLINING_BUDGET) {
                                overBudget++;
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        var out = new StringBuilder();
        out.append(
                String.format(
                        "Endive C2 inlining: %d compilations of generated code, %d used the whole"
                                + " %d B budget%n",
                        compilations, overBudget, INLINING_BUDGET));
        out.append(
                String.format(
                        "%6s %7s %6s %8s  %s%n",
                        "bytes", "nmsize", "sites", "inlined", "callee: failures"));
        callees.entrySet().stream()
                .sorted(
                        Comparator.comparingInt(
                                        (Map.Entry<String, Callee> e) ->
                                                e.getValue().sites - e.getValue().inlined)
                                .thenComparingInt(e -> e.getValue().sites)
                                .reversed())
                .forEach(
                        e -> {
                            var c = e.getValue();
                            var nmsize = nmsizes.get(e.getKey());
                            out.append(
                                    String.format(
                                            "%6d %7s %6d %8d  %s%s%n",
                                            c.bytes,
                                            nmsize == null ? "-" : nmsize,
                                            c.sites,
                                            c.inlined,
                                            c.name,
                                            failures(c)));
                        });
        return out.toString();
    }

    private static String failures(Callee callee) {
        if (callee.failures.isEmpty()) {
            return "";
        }
        return callee.failures.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ", ": ", ""));
    }

    private static String attr(XMLStreamReader xml, String name) {
        return xml.getAttributeValue(null, name);
    }
}
