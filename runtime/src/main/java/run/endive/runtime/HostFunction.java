package run.endive.runtime;

import java.util.List;
import run.endive.wasm.types.FunctionType;

/**
 * A HostFunction is an ExternalFunction that has been defined by the host.
 */
public class HostFunction extends ImportFunction {
    /**
     * @deprecated use {@link #HostFunction(String, String, FunctionType, WasmFunctionHandle)}
     */
    @Deprecated
    public HostFunction(
            String moduleName,
            String symbolName,
            List paramTypes,
            List returnTypes,
            WasmFunctionHandle handle) {
        super(
                moduleName,
                symbolName,
                FunctionType.of(convert(paramTypes), convert(returnTypes)),
                handle);
    }

    public HostFunction(
            String moduleName, String symbolName, FunctionType type, WasmFunctionHandle handle) {
        super(moduleName, symbolName, type, handle);
    }
}
