package run.endive.tools.wasm;

public class WasmSmithException extends RuntimeException {
    public WasmSmithException(String message) {
        super(message);
    }

    public WasmSmithException(String message, Throwable cause) {
        super(message, cause);
    }
}
