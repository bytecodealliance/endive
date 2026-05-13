package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import run.endive.runtime.CompiledModule;
import run.endive.wabt.Wat2WasmModule;
import org.junit.jupiter.api.Test;

public class ImplementsCompiledModuleTest {

    @Test
    public void testImplementsCompiledModule() {
        CompiledModule module = new Wat2WasmModule();
        assertNotNull(module.wasmModule());
        assertNotNull(module.machineFactory());
    }
}
