module run.endive.build.time.compiler {
    requires transitive run.endive.compiler;
    requires run.endive.codegen;
    requires run.endive.runtime;
    requires run.endive.wasm;
    requires com.github.javaparser.core;

    exports run.endive.build.time.compiler;
}
