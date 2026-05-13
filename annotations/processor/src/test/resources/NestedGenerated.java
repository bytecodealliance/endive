package chicory.testing;

import chicory.testing.Box.Nested;
import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.wasm.types.Value;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

import java.util.List;
import javax.annotation.processing.Generated;

@Generated("run.endive.annotations.processor.HostModuleProcessor")
public final class Nested_ModuleFactory {

    private Nested_ModuleFactory() {
    }

    public static HostFunction[] toHostFunctions(Nested functions) {
        return toHostFunctions(functions, "nested");
    }

    public static HostFunction[] toHostFunctions(Nested functions, String moduleName) {
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
