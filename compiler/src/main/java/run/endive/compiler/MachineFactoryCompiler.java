package run.endive.compiler;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import run.endive.compiler.internal.ClassLoadingCollector;
import run.endive.compiler.internal.CompilerResult;
import run.endive.compiler.internal.InterpretableFunctions;
import run.endive.compiler.internal.MachineFactory;
import run.endive.runtime.CompiledModule;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;

/**
 * Compiles WASM function bodies to JVM byte code that can be used as a machine factory for {@link Instance}'s.
 */
public final class MachineFactoryCompiler {

    private static final String INTERPRETED_FUNCTIONS = "interpretedFunctions";

    private MachineFactoryCompiler() {
        // no instances
    }

    /**
     * The compile method reference can be used as machine factory in instance builders.
     * <pre>
     * var instance = Instance.builder(Parser.parse(is))
     *         .withMachineFactory(MachineFactoryCompiler::compile)
     *         .build();
     * </pre>
     * <p>
     * Every instance created by the builder will pay the cost of compiling the module.
     * </p>
     *
     * @see #compile(WasmModule) If you want to compile the module only once for multiple instances.
     */
    public static Machine compile(Instance instance) {
        return compile(instance.module()).apply(instance);
    }

    /**
     * Compiles a machine factory that can used in instance builders.
     * The module is only compiled once and the machine factory is reused for every
     * instance created by the builder.
     * <pre>
     * var module  = Parser.parse(is);
     * var builder = Instance.builder(module)
     *         .withMachineFactory(MachineFactoryCompiler.compile(module));
     * var instance1 = builder.build();
     * var instance2 = builder.build();
     * </pre>
     */
    public static Function<Instance, Machine> compile(WasmModule module) {
        return new MachineFactory(module);
    }

    /**
     * Configures a compiler that can compile a machine factory that can used in instance builders.
     * The builder allows you to configure the compiler options used to compile the module to
     * byte code.
     * This should be used when you want to create multiple instances of the same module.
     * <pre>
     * var module  = Parser.parse(is);
     * var builder = Instance.builder(module)
     *         .withMachineFactory(
     *             MachineFactoryCompiler.builder(module)
     *                 .withInterpreterFallback(InterpreterFallback.FAIL)
     *                 .compile()
     *         );
     * var instance1 = builder.build();
     * var instance2 = builder.build();
     * </pre>
     */
    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public static final class Builder {
        private final WasmModule module;
        private final run.endive.compiler.internal.Compiler.Builder compilerBuilder;
        private Cache cache;
        private boolean methodSplitting;

        private Builder(WasmModule module) {
            this.module = module;
            this.compilerBuilder = run.endive.compiler.internal.Compiler.builder(module);
        }

        public Builder withClassName(String className) {
            compilerBuilder.withClassName(className);
            return this;
        }

        public Builder withMaxFunctionsPerClass(int maxFunctionsPerClass) {
            compilerBuilder.withMaxFunctionsPerClass(maxFunctionsPerClass);
            return this;
        }

        public Builder withInterpreterFallback(InterpreterFallback interpreterFallback) {
            compilerBuilder.withInterpreterFallback(interpreterFallback);
            return this;
        }

        public Builder withInterpretedFunctions(Set<Integer> interpretedFunctions) {
            compilerBuilder.withInterpretedFunctions(interpretedFunctions);
            return this;
        }

        /**
         * Sets the {@link MethodPrefixer} used to name the compiled methods. Defaults to
         * {@link MethodPrefixer#defaultPrefixer()}.
         */
        public Builder withMethodPrefixer(MethodPrefixer methodPrefixer) {
            compilerBuilder.withMethodPrefixer(methodPrefixer);
            return this;
        }

        /** Splits functions too large for the JVM to JIT into smaller methods. Experimental. */
        public Builder withMethodSplitting(boolean methodSplitting) {
            compilerBuilder.withMethodSplitting(methodSplitting);
            this.methodSplitting = methodSplitting;
            return this;
        }

        public Builder withCache(Cache cache) {
            this.cache = cache;
            return this;
        }

        public Function<Instance, Machine> compile() {
            return compileFactory(false);
        }

        /**
         * Compiles the module and returns it together with a machine factory for instances of it,
         * see {@link Instance#builder(CompiledModule)}. The returned module is a copy without
         * custom sections that keeps only the function bodies the interpreter may run, see {@link
         * WasmModule#stripped(Set)}. The module passed to the builder is left unchanged.
         */
        public CompiledModule compileModule() {
            return compileFactory(true);
        }

        private MachineFactory compileFactory(boolean strip) {
            try {

                // Can we load the byte codes from the cache?
                var useCache = cache != null && module.digest() != null;
                var cacheKey = methodSplitting ? "split-" + module.digest() : module.digest();
                if (useCache) {
                    byte[] cachedData = cache.get(cacheKey);
                    if (cachedData != null) {
                        return machineFactory(loadCompilerResult(cachedData), strip);
                    }
                }

                // Compile the byte codes...
                var result =
                        compilerBuilder
                                .withClassCollectorFactory(ClassLoadingCollector::new)
                                .build()
                                .compile();

                if (useCache) {
                    // store results in the cache to speed the next time.
                    cache.putIfAbsent(cacheKey, storeCompilerResult(result));
                }

                return machineFactory(result, strip);
            } catch (IOException e) {
                throw new WasmEngineException(e);
            }
        }

        private MachineFactory machineFactory(CompilerResult result, boolean strip) {
            var factory = ((ClassLoadingCollector) result.collector()).machineFactory();
            var interpreted = result.interpretedFunctions();
            // Cache entries written by older versions don't record the interpreted functions.
            if (!strip || interpreted == null) {
                return new MachineFactory(module, factory);
            }
            var stripped = module.stripped(InterpretableFunctions.of(module, interpreted));
            return new MachineFactory(stripped, factory);
        }
    }

    private static byte[] storeCompilerResult(CompilerResult result) {
        var collector = (ClassLoadingCollector) result.collector();
        try {
            // Create JAR in memory
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (JarOutputStream jos = new JarOutputStream(baos)) {
                // Store the properties file
                var properties = new Properties();
                properties.put("mainClass", collector.mainClassName());
                properties.put(
                        INTERPRETED_FUNCTIONS,
                        result.interpretedFunctions().stream()
                                .map(String::valueOf)
                                .collect(joining(",")));
                ByteArrayOutputStream propsBaos = new ByteArrayOutputStream();
                properties.store(propsBaos, "");

                JarEntry propsEntry = new JarEntry("wasm-module.properties");
                jos.putNextEntry(propsEntry);
                jos.write(propsBaos.toByteArray());
                jos.closeEntry();

                // Store all class files
                for (var entry : collector.classBytes().entrySet()) {
                    var className = entry.getKey().replace('.', '/') + ".class";
                    JarEntry classEntry = new JarEntry(className);
                    jos.putNextEntry(classEntry);
                    jos.write(entry.getValue());
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CompilerResult loadCompilerResult(byte[] jarData) throws IOException {
        var collector = new ClassLoadingCollector();
        Set<Integer> interpretedFunctions = null;

        // It was previously compiled, just load it from JAR.
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(jarData))) {
            var properties = new Properties();
            String mainClass = null;
            Map<String, byte[]> classes = new HashMap<>();

            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.getName().equals("wasm-module.properties")) {
                    // Load properties
                    properties.load(jis);
                    mainClass = properties.getProperty("mainClass");
                    var interpreted = properties.getProperty(INTERPRETED_FUNCTIONS);
                    if (interpreted != null) {
                        interpretedFunctions =
                                Arrays.stream(interpreted.split(","))
                                        .filter(id -> !id.isEmpty())
                                        .map(Integer::valueOf)
                                        .collect(toSet());
                    }
                } else if (entry.getName().endsWith(".class")) {
                    // Load class file
                    String className = entry.getName().replace('/', '.').replace(".class", "");

                    // Read class file bytes
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = jis.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    classes.put(className, baos.toByteArray());
                }
                jis.closeEntry();
            }

            // Add classes to collector
            for (var classEntry : classes.entrySet()) {
                if (classEntry.getKey().equals(mainClass)) {
                    collector.putMainClass(mainClass, classEntry.getValue());
                } else {
                    collector.put(classEntry.getKey(), classEntry.getValue());
                }
            }
        }
        return new CompilerResult(collector, interpretedFunctions);
    }
}
