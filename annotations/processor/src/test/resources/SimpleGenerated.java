package endive.testing;

import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.wasm.types.Value;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

import java.util.List;
import javax.annotation.processing.Generated;

@Generated("run.endive.annotations.processor.HostModuleProcessor")
public final class Simple_ModuleFactory {

    private Simple_ModuleFactory() {
    }

    public static HostFunction[] toHostFunctions(Simple functions) {
        return toHostFunctions(functions, "simple");
    }

    public static HostFunction[] toHostFunctions(Simple functions, String moduleName) {
        return new HostFunction[] { //
                new HostFunction(moduleName,
                        "print",
                        FunctionType.of(
                                List.of(ValType.I32,
                                        ValType.I32),
                                List.of()),
                        (Instance instance, long... args) -> {
                            functions.print(instance.memory().readString((int) args[0],
                                    (int) args[1]));
                            return null;
                        }), //
                new HostFunction(moduleName,
                        "printx",
                        FunctionType.of(
                                List.of(ValType.I32),
                                List.of()),
                        (Instance instance, long... args) -> {
                            functions.printx(instance.memory().readCString((int) args[0]));
                            return null;
                        }), //
                new HostFunction(moduleName,
                        "random_get",
                        FunctionType.of(
                                List.of(ValType.I32,
                                        ValType.I32),
                                List.of()),
                        (Instance instance, long... args) -> {
                            functions.randomGet(instance.memory(),
                                    (int) args[0],
                                    (int) args[1]);
                            return null;
                        }), //
                new HostFunction(moduleName,
                        "exit",
                        FunctionType.of(List.of(), List.of()),
                        (Instance instance, long... args) -> {
                            functions.exit();
                            return null;
                        }) };
    }
}
