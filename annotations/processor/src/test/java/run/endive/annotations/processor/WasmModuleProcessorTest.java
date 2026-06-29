package run.endive.annotations.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

class WasmModuleProcessorTest {

    @Test
    void resolvesWasmModuleFromClassPathNotJustClassOutput() {
        Compilation compilation =
                javac().withProcessors(new WasmModuleProcessor())
                        .compile(JavaFileObjects.forResource("IterFactModule.java"));

        assertThat(compilation).succeededWithoutWarnings();

        assertThat(compilation).generatedSourceFile("endive.testing.IterFactModule_ModuleExports");
    }

    @Test
    void reportsMissingWasmModule() {
        Compilation compilation =
                javac().withProcessors(new WasmModuleProcessor())
                        .compile(JavaFileObjects.forResource("MissingModule.java"));

        assertThat(compilation).failed();

        assertThat(compilation)
                .hadErrorContaining("Failed to load wasmFile")
                .inFile(JavaFileObjects.forResource("MissingModule.java"));
    }
}
