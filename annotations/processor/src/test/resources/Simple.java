package endive.testing;

import static java.util.Objects.requireNonNull;

import run.endive.annotations.Buffer;
import run.endive.annotations.CString;
import run.endive.annotations.HostModule;
import run.endive.annotations.WasmExport;
import run.endive.runtime.Memory;
import run.endive.runtime.HostFunction;
import run.endive.wasm.WasmEngineException;
import java.util.Random;

@HostModule("simple")
public final class Simple {

    private final Random random;

    public Simple(Random random) {
        this.random = requireNonNull(random);
    }

    @WasmExport
    public void print(@Buffer String data) {
        System.out.println(data);
    }

    @WasmExport
    public void printx(@CString String data) {
        System.out.println(data);
    }

    @WasmExport
    public void randomGet(Memory memory, int ptr, int len) {
        byte[] data = new byte[len];
        random.nextBytes(data);
        memory.write(ptr, data);
    }

    @WasmExport
    public void exit() {
        throw new WasmEngineException("exit");
    }

    public HostFunction[] toHostFunctions() {
        return Simple_ModuleFactory.toHostFunctions(this);
    }
}
