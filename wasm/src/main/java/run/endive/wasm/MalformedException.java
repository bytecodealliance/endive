package run.endive.wasm;

/** Wasm spec: binary format is malformed (parsing error). */
public class MalformedException extends WasmEngineException {
    public MalformedException(String msg) {
        super(msg);
    }

    public MalformedException(Throwable cause) {
        super(cause);
    }

    public MalformedException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
