package run.endive.wasm;

/** Wasm spec: module cannot be linked (import/export type mismatches). */
public class UnlinkableException extends WasmEngineException {
    public UnlinkableException(String msg) {
        super(msg);
    }

    public UnlinkableException(Throwable cause) {
        super(cause);
    }

    public UnlinkableException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
