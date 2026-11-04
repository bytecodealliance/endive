package run.endive.wasi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static run.endive.wasi.Files.copyDirectory;

import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import run.endive.log.SystemLogger;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

public final class WasiTestRunner {
    private static final SystemLogger LOGGER = new SystemLogger();
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private WasiTestRunner() {}

    public static void execute(
            File test,
            List<String> args,
            Optional<String> root,
            Map<String, String> env,
            int exitCode,
            Optional<String> stdout) {
        try (FileSystem fs =
                ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder().setAttributeViews("unix").build())) {
            run(test, args, root, fs.getPath("/"), env, exitCode, stdout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void executeOnHost(
            Path tempDir,
            File test,
            List<String> args,
            Optional<String> root,
            Map<String, String> env,
            int exitCode,
            Optional<String> stdout) {
        Map<String, String> hostEnv = env;
        if (WINDOWS && root.isPresent()) {
            // Java does not expose Windows file IDs, so hard links get different inode numbers
            assumeFalse(test.getName().equals("path_link.wasm"), "hard link inode numbers");
            hostEnv = new HashMap<>(env);
            hostEnv.put("NO_RENAME_DIR_TO_EMPTY_DIR", "true");
        }
        run(test, args, root, tempDir.resolve("root"), hostEnv, exitCode, stdout);
    }

    private static void run(
            File test,
            List<String> args,
            Optional<String> root,
            Path target,
            Map<String, String> env,
            int exitCode,
            Optional<String> stdout) {
        var stdoutStream = new MockPrintStream();
        var stderrStream = new MockPrintStream();

        List<String> allArgs = new ArrayList<>();
        allArgs.add("test");
        allArgs.addAll(args);

        WasiOptions.Builder options =
                WasiOptions.builder()
                        .withStdout(stdoutStream)
                        .withStderr(stderrStream)
                        .withArguments(allArgs);

        env.forEach(options::withEnvironment);
        // TODO: dangling filesystem is not supported
        if (!test.getName().contains("environ")) {
            options.withEnvironment("NO_DANGLING_FILESYSTEM", "true");
        }

        if (root.isPresent()) {
            Path source = test.getParentFile().toPath().resolve(root.get());
            try {
                copyDirectory(source, target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            options.withDirectory("/", target);
        }

        int actualExitCode;
        try {
            actualExitCode = runModule(test, options.build());
        } catch (WasiExitException e) {
            actualExitCode = e.exitCode();
        } catch (RuntimeException e) {
            String message = "Failed to execute test: " + test;
            if (!stdoutStream.output().isEmpty() || !stderrStream.output().isEmpty()) {
                message += "\n<<<<<\n";
                message += (stdoutStream.output() + stderrStream.output()).strip();
                message += "\n>>>>>";
            }
            throw new RuntimeException(message, e);
        }

        assertEquals(exitCode, actualExitCode, "exit code");
        stdout.ifPresent(expected -> assertEquals(expected, stdoutStream.output(), "stdout"));
    }

    private static int runModule(File test, WasiOptions wasiOptions) {
        try (var wasi =
                WasiPreview1.builder().withLogger(LOGGER).withOptions(wasiOptions).build()) {
            Instance.builder(Parser.parse(test))
                    .withImportValues(
                            ImportValues.builder().addFunction(wasi.toHostFunctions()).build())
                    .build();
        } catch (WasiExitException e) {
            return e.exitCode();
        }
        return 0;
    }
}
