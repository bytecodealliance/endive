package run.endive.runtime;

import run.endive.wasm.WasmEngineException;

/** Wasm spec: runtime trap during execution (unreachable, OOB access, null ref, etc.). */
public class TrapException extends WasmEngineException {
    public TrapException(String msg) {
        super(msg);
    }
}
