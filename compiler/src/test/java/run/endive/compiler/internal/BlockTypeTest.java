package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.BlockTypeTestModule;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

public class BlockTypeTest {

    @Test
    public void shouldCompileTypeIndicesThatOverlapValueTypes() {
        var module = Parser.parse(BlockTypeTestModule.create());
        var instance =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();

        for (var typeIndex = BlockTypeTestModule.FIRST_TYPE_INDEX;
                typeIndex <= BlockTypeTestModule.LAST_TYPE_INDEX;
                typeIndex++) {
            var result = instance.export(BlockTypeTestModule.exportName(typeIndex)).apply();
            assertEquals(typeIndex, result[0]);
        }
    }
}
