package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

@FunctionalInterface
public interface Machine {

    long[] call(int funcId, long[] args) throws WasmEngineException;

    default long[] call(int funcId, long[] args, Object[] refArgs) throws WasmEngineException {
        return call(funcId, args);
    }

    default CallResult callWithRefs(int funcId, long[] args, Object[] refArgs)
            throws WasmEngineException {
        return new CallResult(call(funcId, args, refArgs), null);
    }
}
