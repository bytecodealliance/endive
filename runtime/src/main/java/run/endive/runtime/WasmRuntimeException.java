package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

/** Engine-specific runtime error in compiled code paths (e.g., OOB memory access). */
public class WasmRuntimeException extends WasmEngineException {
    public WasmRuntimeException(String msg) {
        super(msg);
    }

    public WasmRuntimeException(Throwable cause) {
        super(cause);
    }

    public WasmRuntimeException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
