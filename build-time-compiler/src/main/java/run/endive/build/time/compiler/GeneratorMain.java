package run.endive.build.time.compiler;

import java.io.IOException;
import java.nio.file.Path;
import run.endive.compiler.InterpreterFallback;

public final class GeneratorMain {

    private GeneratorMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: GeneratorMain <wasmFile> <name> <targetClassFolder>"
                            + " <targetSourceFolder> <targetWasmFolder>"
                            + " [interpreterFallback] [moduleInterface]");
        }
        var configBuilder =
                Config.builder()
                        .withWasmFile(Path.of(args[0]))
                        .withName(args[1])
                        .withTargetClassFolder(Path.of(args[2]))
                        .withTargetSourceFolder(Path.of(args[3]))
                        .withTargetWasmFolder(Path.of(args[4]));
        if (args.length > 5 && !args[5].isEmpty()) {
            configBuilder.withInterpreterFallback(InterpreterFallback.valueOf(args[5]));
        }
        if (args.length > 6 && !args[6].isEmpty()) {
            configBuilder.withModuleInterface(args[6]);
        }
        var config = configBuilder.build();
        var generator = new Generator(config);
        var interpreted = generator.generateResources();
        generator.generateMetaWasm(interpreted);
        generator.generateSources();
        if (config.moduleInterface() != null && !config.moduleInterface().isEmpty()) {
            generator.generateModuleInterface(config.moduleInterface());
        }
    }
}
