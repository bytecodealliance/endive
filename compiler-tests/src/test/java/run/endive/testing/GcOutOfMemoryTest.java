package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;

public class GcOutOfMemoryTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/gc_oom_test.wat.wasm"));

    private Instance interpreterInstance() {
        return Instance.builder(MODULE).withMachineFactory(InterpreterMachine::new).build();
    }

    private Instance compilerInstance() {
        return Instance.builder(MODULE).withMachineFactory(MachineFactoryCompiler::compile).build();
    }

    private void assertOom(Instance instance, String funcName) {
        var fn = instance.export(funcName);
        var ex = assertThrows(WasmEngineException.class, () -> fn.apply(Integer.MAX_VALUE));
        assertTrue(ex.getMessage().contains("out of memory"));
    }

    // array.new_default with i32 elements

    @Test
    public void arrayNewDefaultI32Interpreter() {
        assertOom(interpreterInstance(), "array_new_default_i32");
    }

    @Test
    public void arrayNewDefaultI32Compiler() {
        assertOom(compilerInstance(), "array_new_default_i32");
    }

    // array.new with i32 fill value

    @Test
    public void arrayNewI32Interpreter() {
        assertOom(interpreterInstance(), "array_new_i32");
    }

    @Test
    public void arrayNewI32Compiler() {
        assertOom(compilerInstance(), "array_new_i32");
    }

    // array.new_default with reference elements

    @Test
    public void arrayNewDefaultRefInterpreter() {
        assertOom(interpreterInstance(), "array_new_default_ref");
    }

    @Test
    public void arrayNewDefaultRefCompiler() {
        assertOom(compilerInstance(), "array_new_default_ref");
    }

    // array.new with reference fill value

    @Test
    public void arrayNewRefInterpreter() {
        assertOom(interpreterInstance(), "array_new_ref");
    }

    @Test
    public void arrayNewRefCompiler() {
        assertOom(compilerInstance(), "array_new_ref");
    }

    // struct.new_default works normally (no OOM on small struct)

    @Test
    public void structNewDefaultNormalInterpreter() {
        var instance = interpreterInstance();
        var result = instance.export("struct_new_default").apply();
        assertEquals(0, result[0]);
    }

    @Test
    public void structNewDefaultNormalCompiler() {
        var instance = compilerInstance();
        var result = instance.export("struct_new_default").apply();
        assertEquals(0, result[0]);
    }
}
