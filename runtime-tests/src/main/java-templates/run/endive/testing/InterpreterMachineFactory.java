package run.endive.testing;

import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;

public class InterpreterMachineFactory {

    public static InterpreterMachine create(Instance instance) {
        return new InterpreterMachine(instance);
    }

}
