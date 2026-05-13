import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.wasm.types.Value;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

import java.util.List;
import javax.annotation.processing.Generated;

@Generated("run.endive.annotations.processor.HostModuleProcessor")
public final class NoPackage_ModuleFactory {

    private NoPackage_ModuleFactory() {
    }

    public static HostFunction[] toHostFunctions(NoPackage functions) {
        return toHostFunctions(functions, "nopackage");
    }

    public static HostFunction[] toHostFunctions(NoPackage functions, String moduleName) {
        return new HostFunction[] { //
                new HostFunction(moduleName,
                        "print",
                        FunctionType.of(
                                List.of(ValType.I32,
                                        ValType.I32),
                                List.of()),
                        (Instance instance, long... args) -> {
                            functions.print(instance.memory(),
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
