package endive.testing;

import run.endive.annotations.WasmModuleInterface;

@WasmModuleInterface("does-not-exist.wasm")
public class MissingModule {}
