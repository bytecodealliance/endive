package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static run.endive.corpus.WatGenerator.callIndirectTargets;

import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;

public class CallIndirectTest {

    private static final int FUNC_COUNT = 1100;

    @Test
    public void callsManyFunctionsOfOneType() {
        var instance =
                Instance.builder(Parser.parse(Wat2Wasm.parse(callIndirectTargets(FUNC_COUNT))))
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        for (int k : new int[] {0, 1, 511, 512, 600, FUNC_COUNT - 1}) {
            long result = instance.export("call").apply(k, 1_000_000_000_000L, 7)[0];
            assertEquals(1_000_000_000_000L + 7 + k, result);
        }
    }
}
