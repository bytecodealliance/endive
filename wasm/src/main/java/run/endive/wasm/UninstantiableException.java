package run.endive.wasm;

/** Wasm spec: module cannot be instantiated (trap during initialization). */
public class UninstantiableException extends WasmEngineException {
    public UninstantiableException(String msg) {
        super(msg);
    }

    public UninstantiableException(Throwable cause) {
        super(cause);
    }

    public UninstantiableException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
