package chicory.testing;

import run.endive.annotations.HostModule;
import run.endive.annotations.WasmExport;

@HostModule("bad_return")
public final class InvalidReturn {

    @WasmExport
    public String toString(int x) {
        return String.valueOf(x);
    }
}
