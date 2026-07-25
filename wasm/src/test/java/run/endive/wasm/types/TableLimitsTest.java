package run.endive.wasm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.wasm.WasmEngineException;

class TableLimitsTest {
    @Test
    void grow() {
        var limits = new TableLimits(5, 10);
        limits.grow(3);
        assertEquals(8, limits.min());
        assertEquals(10, limits.max());

        var limits2 = new TableLimits(5, 10);
        var e = assertThrows(WasmEngineException.class, () -> limits2.grow(20));
        assertEquals("cannot grow past maximum", e.getMessage());
    }
}
