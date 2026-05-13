package run.endive.wasm;

/** Wasm spec: module fails validation (type errors, constraint violations). */
public class InvalidException extends WasmEngineException {
    public InvalidException(String msg) {
        super(msg);
    }

    public InvalidException(Throwable cause) {
        super(cause);
    }

    public InvalidException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
