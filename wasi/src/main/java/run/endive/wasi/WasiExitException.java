package run.endive.wasi;

import run.endive.wasm.WasmEngineException;

/** WASI proc_exit with a specific exit code. */
public class WasiExitException extends WasmEngineException {
    private final int exitCode;

    public WasiExitException(int exitCode) {
        super("Process exit code: " + exitCode);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }

    // no need to capture the Stack Trace
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
