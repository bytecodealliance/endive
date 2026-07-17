// Suppress "module not found" warnings (and unfortunately all other module warnings) for forward
// references of qualified exports (`exports ... to ...`), see https://stackoverflow.com/q/53670052
@SuppressWarnings("module")
module run.endive.runtime {
    requires transitive run.endive.wasm;

    exports run.endive.runtime;
    exports run.endive.runtime.alloc;
    exports run.endive.runtime.internal to
            run.endive.compiler;
}
