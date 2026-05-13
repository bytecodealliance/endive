package run.endive.runtime;

import run.endive.wasm.ChicoryException;

@FunctionalInterface
public interface Machine {

    long[] call(int funcId, long[] args) throws ChicoryException;
}
