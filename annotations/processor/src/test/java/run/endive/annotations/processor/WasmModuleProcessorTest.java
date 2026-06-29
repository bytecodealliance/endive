package run.endive.annotations.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

class WasmModuleProcessorTest {

    private static Compilation compile(String source) {
        return javac().withProcessors(new WasmModuleProcessor())
                .compile(JavaFileObjects.forResource(source));
    }

    @Test
    void resolvesWasmModuleFromClassPathNotJustClassOutput() {
        Compilation compilation = compile("IterFactModule.java");

        assertThat(compilation).succeededWithoutWarnings();

        assertThat(compilation).generatedSourceFile("endive.testing.IterFactModule_ModuleExports");
    }

    @Test
    void reportsMissingWasmModule() {
        Compilation compilation = compile("MissingModule.java");

        assertThat(compilation).failed();

        assertThat(compilation)
                .hadErrorContaining("Failed to load wasmFile")
                .inFile(JavaFileObjects.forResource("MissingModule.java"));
    }

    @Test
    void skipsTagExportAndStillGeneratesFunctionExport() {
        Compilation compilation = compile("TagExportModule.java");

        assertThat(compilation).succeededWithoutWarnings();

        assertThat(compilation).generatedSourceFile("endive.testing.TagExportModule_ModuleExports");
    }

    @Test
    void generatesTagImportBindingAlongsideFunctionImport() {
        Compilation compilation = compile("TagImportModule.java");

        assertThat(compilation).succeededWithoutWarnings();

        assertThat(compilation).generatedSourceFile("endive.testing.TagImportModule_Host");
        assertThat(compilation).generatedSourceFile("endive.testing.TagImportModule_ModuleImports");
    }
}
