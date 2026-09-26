package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Fuel;
import run.endive.runtime.Instance;
import run.endive.runtime.WasmOutOfFuelException;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

public class FuelTest {

    // A loop with no exit condition. Without a budget it never returns.
    private static final String ENDLESS = "(module (func (export \"run\") (loop $l (br $l))))";

    // A loop that runs a fixed number of times, so a generous budget must let it finish.
    private static final String COUNTED =
            "(module (func (export \"run\") (result i32) (local $i i32)"
                    + " (loop $l (local.set $i (i32.add (local.get $i) (i32.const 1)))"
                    + " (br_if $l (i32.lt_s (local.get $i) (i32.const 1000))))"
                    + " (local.get $i)))";

    @AfterEach
    public void stopMetering() {
        Fuel.clear();
    }

    @Test
    public void endlessLoopRunsOutOfFuel() {
        var run = compiled(ENDLESS).export("run");

        Fuel.set(10_000);
        assertThrows(WasmOutOfFuelException.class, run::apply);
        assertEquals(0, Fuel.remaining());
    }

    @Test
    public void aBudgetedProgramStillFinishes() {
        var run = compiled(COUNTED).export("run");

        Fuel.set(10_000);
        assertEquals(1000, run.apply()[0]);
        assertTrue(Fuel.remaining() > 0);
    }

    @Test
    public void withoutABudgetNothingIsMetered() {
        var run = compiled(COUNTED).export("run");

        assertEquals(Fuel.UNLIMITED, Fuel.remaining());
        assertEquals(1000, run.apply()[0]);
        assertEquals(Fuel.UNLIMITED, Fuel.remaining());
    }

    @Test
    public void aBudgetAppliesOnlyToTheThreadThatSetIt() throws Exception {
        var run = compiled(COUNTED).export("run");
        Fuel.set(10);

        var other = new Thread(() -> assertEquals(Fuel.UNLIMITED, Fuel.remaining()));
        other.start();
        other.join();

        assertThrows(WasmOutOfFuelException.class, run::apply);
    }

    @Test
    public void theInterpreterIsMeteredToo() {
        // Fuel is not a compiler-only feature: an API that silently did nothing on the
        // interpreter would be its own trap.
        WasmModule module = Parser.parse(Wat2Wasm.parse(ENDLESS));
        var run = Instance.builder(module).build().export("run");

        Fuel.set(10_000);
        assertThrows(WasmOutOfFuelException.class, run::apply);
    }

    private static Instance compiled(String wat) {
        WasmModule module = Parser.parse(Wat2Wasm.parse(wat));
        return Instance.builder(module)
                .withMachineFactory(MachineFactoryCompiler.compile(module))
                .build();
    }
}
