package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

@FunctionalInterface
public interface Machine {

    long[] call(int funcId, long[] args) throws WasmEngineException;
}
