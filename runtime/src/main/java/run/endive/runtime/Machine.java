package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

@FunctionalInterface
public interface Machine {

    long[] call(int funcId, long[] args) throws WasmEngineException;

    default long[] call(int funcId, long[] args, Object[] refArgs) throws WasmEngineException {
        return call(funcId, args);
    }

    default Object[] callGc(int funcId, Object[] args) throws WasmEngineException {
        throw new UnsupportedOperationException("This Machine does not support GC references");
    }
}
