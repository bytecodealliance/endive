package endive.testing;

import run.endive.annotations.HostModule;
import run.endive.annotations.WasmExport;
import run.endive.runtime.HostFunction;
import run.endive.runtime.Memory;
import run.endive.wasm.ChicoryException;

public class Box {

    @HostModule("nested")
    public final class Nested {

        @WasmExport
        public void print(Memory memory, int ptr, int len) {
            System.out.println(memory.readString(ptr, len));
        }

        @WasmExport
        public void exit() {
            throw new ChicoryException("exit");
        }

        public HostFunction[] toHostFunctions() {
            return Nested_ModuleFactory.toHostFunctions(this);
        }
    }

}
