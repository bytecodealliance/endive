package run.endive.tools.wasm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import run.endive.log.Logger;
import run.endive.log.SystemLogger;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasi.WasiExitException;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.WasmModule;

public final class Validate {

    private static final Logger logger =
            new SystemLogger() {
                @Override
                public boolean isLoggable(Logger.Level level) {
                    return false;
                }
            };
    private static final WasmModule MODULE = WasmToolsModule.load();

    private final List<String> features;

    private Validate(List<String> features) {
        this.features = features;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static void validate(File file) {
        try (var is = new FileInputStream(file)) {
            validate(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void validate(String wat) {
        try (var is = new ByteArrayInputStream(wat.getBytes(StandardCharsets.UTF_8))) {
            validate(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void validate(InputStream is) {
        doValidate(is, Collections.emptyList());
    }

    public void validateModule(File file) {
        try (var is = new FileInputStream(file)) {
            validateModule(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void validateModule(String wat) {
        try (var is = new ByteArrayInputStream(wat.getBytes(StandardCharsets.UTF_8))) {
            validateModule(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void validateModule(InputStream is) {
        doValidate(is, features);
    }

    private static void doValidate(InputStream is, List<String> features) {
        try (var stdinStream = new ByteArrayInputStream(is.readAllBytes());
                var stdoutStream = new ByteArrayOutputStream();
                var stderrStream = new ByteArrayOutputStream()) {

            List<String> args = new ArrayList<>();
            args.add("wasm-tools");
            args.add("validate");
            if (!features.isEmpty()) {
                args.add("--features");
                args.add(String.join(",", features));
            }
            args.add("-");

            var options =
                    WasiOptions.builder()
                            .withStdin(stdinStream, false)
                            .withStdout(stdoutStream, false)
                            .withStderr(stderrStream, false)
                            .withArguments(args)
                            .build();

            logger.info("Running command: " + String.join(" ", options.arguments()));

            try (var wasi =
                    WasiPreview1.builder().withLogger(logger).withOptions(options).build()) {
                var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();

                try {
                    Instance.builder(MODULE)
                            .withMachineFactory(WasmToolsModule::create)
                            .withMemoryFactory(ByteArrayMemory::new)
                            .withImportValues(imports)
                            .build();
                } catch (WasiExitException e) {
                    if (e.exitCode() != 0) {
                        throw new WatParseException(
                                stdoutStream.toString(StandardCharsets.UTF_8)
                                        + stderrStream.toString(StandardCharsets.UTF_8),
                                e);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static final class Builder {
        private final List<String> features = new ArrayList<>();

        private Builder() {}

        public Builder withFeatures(WasmFeature... features) {
            for (WasmFeature f : features) {
                this.features.add(f.flag());
            }
            return this;
        }

        public Builder withoutFeature(WasmFeature feature) {
            this.features.add(feature.negatedFlag());
            return this;
        }

        public Validate build() {
            return new Validate(Collections.unmodifiableList(new ArrayList<>(features)));
        }
    }
}
