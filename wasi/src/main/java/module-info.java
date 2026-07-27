module run.endive.wasi {
    requires static run.endive.annotations;
    requires run.endive.log;
    requires transitive run.endive.runtime;

    // Needed for `javax.annotation.processing.Generated` annotation in generated ModuleFactory
    requires static java.compiler;

    exports run.endive.wasi;
}
