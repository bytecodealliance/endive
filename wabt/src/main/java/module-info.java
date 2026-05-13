module run.endive.wabt {
    requires run.endive.log;
    requires run.endive.runtime;
    requires run.endive.wasi;
    requires run.endive.wasm;
    requires io.roastedroot.zerofs.ZeroFs;

    exports run.endive.wabt;
}
