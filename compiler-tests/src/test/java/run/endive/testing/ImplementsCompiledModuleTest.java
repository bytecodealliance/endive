package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import run.endive.runtime.CompiledModule;
import run.endive.wabt.Wat2WasmModule;

public class ImplementsCompiledModuleTest {

    @Test
    public void testImplementsCompiledModule() {
        CompiledModule module = new Wat2WasmModule();
        assertNotNull(module.wasmModule());
        assertNotNull(module.machineFactory());
    }
}
