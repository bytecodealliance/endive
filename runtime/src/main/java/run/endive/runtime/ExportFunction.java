package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

/**
 * This represents an Exported function from the Wasm module.
 */
@FunctionalInterface
public interface ExportFunction {
    long[] apply(long... args) throws WasmEngineException;

    default CallResult applyWithRefs(long[] args, Object[] refArgs) throws WasmEngineException {
        throw new UnsupportedOperationException("This function does not support applyWithRefs");
    }
}
