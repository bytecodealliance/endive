package run.endive.runtime;

import run.endive.wasm.ChicoryException;

/**
 * This represents an Exported function from the Wasm module.
 */
@FunctionalInterface
public interface ExportFunction {
    long[] apply(long... args) throws ChicoryException;
}
