package run.endive.build.time.maven;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import run.endive.build.time.compiler.Config;
import run.endive.build.time.compiler.Generator;
import run.endive.compiler.InterpreterFallback;
import run.endive.redline.experimental.build.RedlineGenerator;

/**
 * This plugin generates an invokable library from the compiled Wasm
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class EndiveCompilerGenMojo extends AbstractMojo {

    /**
     * the wasm module to be used
     */
    @Parameter(required = true)
    private File wasmFile;

    /**
     * the base name to be used for the generated classes
     */
    @Parameter(required = true)
    private String name;

    /**
     * the target folder to generate classes
     */
    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/generated-resources/endive-compiler")
    private File targetClassFolder;

    /**
     * the target source folder to generate the Machine implementation
     */
    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/generated-sources/endive-compiler")
    private File targetSourceFolder;

    /**
     * the target wasm folder to generate the stripped meta wasm module
     */
    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/generated-resources/endive-compiler")
    private File targetWasmFolder;

    /**
     * the action to take if the compiler needs to use the interpreter because a function is too big
     */
    @Parameter(required = true, defaultValue = "FAIL")
    InterpreterFallback interpreterFallback;

    /**
     * The indexes of functions that should be interpreted, separated by commas
     */
    @Parameter(required = false, defaultValue = "")
    Set<Integer> interpretedFunctions;

    /**
     * Fully qualified name of the user's class that will use the compiled module.
     * When set, the plugin generates _ModuleExports and _ModuleImports wrapper classes,
     * eliminating the need for @WasmModuleInterface annotation and the annotation processor.
     */
    @Parameter(required = false)
    String moduleInterface;

    /**
     * Enable Redline native compilation (experimental) for all supported
     * platforms (x86_64 and aarch64 on Linux, macOS, and Windows).
     */
    @Parameter(required = false, defaultValue = "false")
    boolean redlineExperimental;

    /**
     * Target triples for Redline native compilation. Overrides {@code redlineExperimental}
     * for fine-grained control over which platforms to cross-compile for.
     */
    @Parameter(required = false)
    List<String> redlineTargets;

    /**
     * The current Maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Compiling classes for " + name + " from " + wasmFile);

        var configBuilder =
                Config.builder()
                        .withWasmFile(wasmFile.toPath())
                        .withName(name)
                        .withTargetClassFolder(targetClassFolder.toPath())
                        .withTargetSourceFolder(targetSourceFolder.toPath())
                        .withTargetWasmFolder(targetWasmFolder.toPath())
                        .withInterpreterFallback(interpreterFallback)
                        .withInterpretedFunctions(interpretedFunctions)
                        .withModuleInterface(moduleInterface);
        if (redlineTargets != null && !redlineTargets.isEmpty()) {
            configBuilder.withRedlineTargets(redlineTargets);
        } else if (redlineExperimental) {
            configBuilder.withRedlineTargets(RedlineGenerator.allTargets());
        }
        var config = configBuilder.build();

        var generator = new Generator(config);

        try {
            var finalInterpretedFunctions = generator.generateResources();
            generator.generateMetaWasm(finalInterpretedFunctions);
            generator.generateSources();

            if (config.hasRedlineTargets()) {
                getLog().info("Redline native compilation for targets: " + config.redlineTargets());
                var redlineGenerator = new RedlineGenerator(config);
                redlineGenerator.generateNativeCode();
                redlineGenerator.extendGeneratedSources();
            }

            if (moduleInterface != null && !moduleInterface.isEmpty()) {
                generator.generateModuleInterface(moduleInterface);
            }

            if (interpreterFallback == InterpreterFallback.WARN
                    && !finalInterpretedFunctions.isEmpty()) {
                var sorted = new TreeSet<>(finalInterpretedFunctions);
                StringBuilder sb = new StringBuilder();
                sb.append("<interpretedFunctions>\n");
                for (Integer funcId : sorted) {
                    sb.append("  <function>").append(funcId).append("</function>\n");
                }
                sb.append("</interpretedFunctions>");
                getLog().warn(
                                "Copy-paste the following to pre-declare interpreted functions"
                                        + " in your pom.xml:\n"
                                        + sb);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate resources", e);
        }

        Resource resource = new Resource();
        resource.setDirectory(targetClassFolder.getPath());
        project.addResource(resource);
        project.addCompileSourceRoot(targetSourceFolder.getPath());
    }
}
