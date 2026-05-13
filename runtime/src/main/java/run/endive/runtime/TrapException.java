package run.endive.runtime;

import run.endive.wasm.ChicoryException;

public class TrapException extends ChicoryException {
    public TrapException(String msg) {
        super(msg);
    }
}
