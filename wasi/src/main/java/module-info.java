module run.endive.wasi {
    requires static run.endive.annotations;
    requires run.endive.log;
    requires transitive run.endive.runtime;
    requires static java.compiler;

    exports run.endive.wasi;
}
