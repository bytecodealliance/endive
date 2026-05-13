package run.endive.wasm;

/**
 * Base exception for errors raised by the Wasm engine (parsing, validation, linking,
 * instantiation, or execution). Distinct from {@link run.endive.runtime.WasmException},
 * which represents Wasm-level tagged exceptions from the exception-handling proposal.
 */
public class WasmEngineException extends RuntimeException {
    public WasmEngineException(String msg) {
        super(msg);
    }

    public WasmEngineException(Throwable cause) {
        super(cause);
    }

    public WasmEngineException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
