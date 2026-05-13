package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

/** Thrown when the host interrupts a running Wasm execution via thread interruption. */
public class WasmInterruptedException extends WasmEngineException {
    public WasmInterruptedException(String msg) {
        super(msg);
    }

    public WasmInterruptedException(Throwable cause) {
        super(cause);
    }

    public WasmInterruptedException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
