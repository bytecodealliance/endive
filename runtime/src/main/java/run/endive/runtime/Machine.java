package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

@FunctionalInterface
public interface Machine extends AutoCloseable {

    long[] call(int funcId, long[] args) throws WasmEngineException;

    default long[] call(int funcId, long[] args, Object[] refArgs) throws WasmEngineException {
        return call(funcId, args);
    }

    /** Call function {@code funcId} with separate numeric and Object ref arguments, returning a {@link CallResult}. */
    default CallResult callWithRefs(int funcId, long[] args, Object[] refArgs)
            throws WasmEngineException {
        return CallResult.of(call(funcId, args, refArgs), null);
    }

    /** Releases what this machine holds, or throws IllegalStateException releasing nothing. */
    @Override
    default void close() {}

    /** Instead of close() when the build fails: release only what other instances wait on. */
    default void abandon() {}
}
