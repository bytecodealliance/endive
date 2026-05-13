package run.endive.runtime;

import run.endive.wasm.ChicoryException;

public class ChicoryInterruptedException extends ChicoryException {
    public ChicoryInterruptedException(String msg) {
        super(msg);
    }

    public ChicoryInterruptedException(Throwable cause) {
        super(cause);
    }

    public ChicoryInterruptedException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
