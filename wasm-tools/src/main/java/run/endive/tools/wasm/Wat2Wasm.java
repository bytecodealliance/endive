package run.endive.tools.wasm;

import static run.endive.tools.wasm.Validate.validate;

import run.endive.log.Logger;
import run.endive.log.SystemLogger;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasi.WasiExitException;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.WasmModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Wat2Wasm {
    private Wat2Wasm() {}

    private static final Logger logger =
            new SystemLogger() {
                @Override
                public boolean isLoggable(Logger.Level level) {
                    return false;
                }
            };
    private static final WasmModule MODULE = WasmToolsModule.load();

    public static byte[] parse(File file) {
        try (var is = new FileInputStream(file)) {
            return parse(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] parse(String wat) {
        try (var is = new ByteArrayInputStream(wat.getBytes(StandardCharsets.UTF_8))) {
            return parse(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] parse(InputStream is) {
        try (var stdinStream = new ByteArrayInputStream(is.readAllBytes());
                var stdoutStream = new ByteArrayOutputStream();
                var stderrStream = new ByteArrayOutputStream()) {

            validate(stdinStream);
            stdinStream.reset();

            var options =
                    WasiOptions.builder()
                            .withStdin(stdinStream, false)
                            .withStdout(stdoutStream, false)
                            .withStderr(stderrStream, false)
                            .withArguments(List.of("wasm-tools", "parse", "-"))
                            .build();

            logger.info("Running command: " + String.join(" ", options.arguments()));

            try (var wasi =
                    WasiPreview1.builder().withLogger(logger).withOptions(options).build()) {
                var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();

                Instance.builder(MODULE)
                        .withMachineFactory(WasmToolsModule::create)
                        .withMemoryFactory(ByteArrayMemory::new)
                        .withImportValues(imports)
                        .build();
            } catch (WasiExitException e) {
                if (e.exitCode() != 0 || stdoutStream.size() <= 0) {
                    throw new WatParseException(
                            stdoutStream.toString(StandardCharsets.UTF_8)
                                    + stderrStream.toString(StandardCharsets.UTF_8),
                            e);
                }
            }
            return stdoutStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
