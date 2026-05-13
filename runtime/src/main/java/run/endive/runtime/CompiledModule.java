package run.endive.runtime;

import java.util.function.Function;
import run.endive.wasm.WasmModule;

/**
 * This interface is implemented by build time compiled wasm modules.
 */
public interface CompiledModule {
    WasmModule wasmModule();

    Function<Instance, Machine> machineFactory();
}
