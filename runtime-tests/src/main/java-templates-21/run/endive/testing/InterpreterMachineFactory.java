package run.endive.testing;

import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.simd.SimdInterpreterMachine;

public class InterpreterMachineFactory {

    public static Machine create(Instance instance) {
        return new SimdInterpreterMachine(instance);
    }

}
