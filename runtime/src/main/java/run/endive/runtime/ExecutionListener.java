package run.endive.runtime;

import run.endive.wasm.types.Instruction;

@FunctionalInterface
public interface ExecutionListener {
    /*
     * WARNING:
     *
     * Implementing this function you will be executing code on the very hot path of the interpreter for each and every instruction.
     * Any issue or performance degradation caused by this code is not going to be supported.
     * This interface along with its usage is experimental and we might drop it at a later stage.
     *
     * If you have a specific use case for this functionality, please, open an Issue at: https://github.run/endive/issues
     */
    void onExecution(Instruction instruction, MStack stack);
}
