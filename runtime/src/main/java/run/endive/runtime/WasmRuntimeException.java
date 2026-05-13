package run.endive.runtime;

import run.endive.wasm.ChicoryException;

public class WasmRuntimeException extends ChicoryException {
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
