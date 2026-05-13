package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

/** Signals successful completion of execution (used by WASI proc_exit with code 0). */
public class ExecutionCompletedException extends WasmEngineException {

    public ExecutionCompletedException(String msg) {
        super(msg);
    }

    public ExecutionCompletedException(Throwable cause) {
        super(cause);
    }

    public ExecutionCompletedException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
