package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;
import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/** Tests for the `none` bottom type of the `any` hierarchy. */
public class NoneTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/none.wat.wasm"));

    private static void assertAllExportsReturnNull(Function<Instance, Machine> machineFactory) {
        var instance = Instance.builder(MODULE).withMachineFactory(machineFactory).build();
        var exports = MODULE.exportSection();
        for (int i = 0; i < exports.exportCount(); i++) {
            var name = exports.getExport(i).name();
            assertEquals(1L, instance.export(name).apply()[0], name);
        }
    }

    @Test
    public void interpreted() {
        assertAllExportsReturnNull(InterpreterMachine::new);
    }

    @Test
    public void compiled() {
        assertAllExportsReturnNull(MachineFactoryCompiler::compile);
    }
}
