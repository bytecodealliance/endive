import run.endive.annotations.HostModule;
import run.endive.annotations.WasmExport;
import run.endive.runtime.Memory;
import run.endive.runtime.HostFunction;
import run.endive.wasm.WasmEngineException;

@HostModule("nopackage")
public final class NoPackage {

    @WasmExport
    public void print(Memory memory, int ptr, int len) {
        System.out.println(memory.readString(ptr, len));
    }

    @WasmExport
    public void exit() {
        throw new WasmEngineException("exit");
    }

    public HostFunction[] toHostFunctions() {
        return NoPackage_ModuleFactory.toHostFunctions(this);
    }
}
