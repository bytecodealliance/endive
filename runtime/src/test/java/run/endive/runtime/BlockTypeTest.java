package run.endive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import run.endive.corpus.BlockTypeTestModule;
import run.endive.wasm.Parser;

public class BlockTypeTest {

    @Test
    public void shouldInterpretTypeIndicesThatOverlapValueTypes() {
        var instance = Instance.builder(Parser.parse(BlockTypeTestModule.create())).build();

        for (var typeIndex = BlockTypeTestModule.FIRST_TYPE_INDEX;
                typeIndex <= BlockTypeTestModule.LAST_TYPE_INDEX;
                typeIndex++) {
            var result = instance.export(BlockTypeTestModule.exportName(typeIndex)).apply();
            assertEquals(typeIndex, result[0]);
        }
    }
}
