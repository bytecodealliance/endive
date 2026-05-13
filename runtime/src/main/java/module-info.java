module run.endive.runtime {
    requires transitive run.endive.wasm;

    exports run.endive.runtime;
    exports run.endive.runtime.alloc;
    exports run.endive.runtime.internal;
}
