package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

/** Thrown when a running Wasm execution exhausts the fuel budget set by the host. */
public class WasmOutOfFuelException extends WasmEngineException {
    public WasmOutOfFuelException(String msg) {
        super(msg);
    }

    public WasmOutOfFuelException(Throwable cause) {
        super(cause);
    }

    public WasmOutOfFuelException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
