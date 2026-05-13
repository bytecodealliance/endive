package endive.testing;

import run.endive.annotations.HostModule;
import run.endive.annotations.WasmExport;
import java.math.BigDecimal;

@HostModule("bad_param")
public final class InvalidParameterUnsupported {

    @WasmExport
    public long square(BigDecimal x) {
        return x.pow(2);
    }
}
