package run.endive.experimental.aot;

import run.endive.compiler.internal.MachineFactory;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.wasm.WasmEngineException;

/**
 * Machine implementation that compiles WASM function bodies to JVM byte code.
 * All compilation is done in a single compile phase during instantiation.
 * <p>
 * This class is deprecated and will be removed in a future version. Please use
 * the {@link run.endive.compiler.MachineFactoryCompiler} instead.
 */
@Deprecated(since = "1.4.0")
public final class AotMachine implements Machine {

    private final Machine machine;

    /**
     * Creates a new AOT machine instance.
     * <p>
     * Please use the {@link run.endive.compiler.MachineFactoryCompiler#compile(Instance)} method instead.
     *
     * @param instance the instance to use for the machine
     */
    @Deprecated(since = "1.4.0")
    public AotMachine(Instance instance) {
        this.machine = new MachineFactory(instance.module()).apply(instance);
    }

    @Override
    public long[] call(int funcId, long[] args) throws WasmEngineException {
        return machine.call(funcId, args);
    }
}
