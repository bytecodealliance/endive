package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import org.junit.jupiter.api.Test;

public class CallTest {

    @Test
    public void callLotsOfArgs() throws InterruptedException {
        var module = Parser.parse(CorpusResources.getResource("compiled/lots-of-args.wat.wasm"));
        var instance =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();

        var function = instance.export("test");
        long[] result = function.apply(2, 3);
        assertArrayEquals(new long[] {5}, result);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void callLotsOfArgsOnDeprecatedAotMachine() throws InterruptedException {
        var module = Parser.parse(CorpusResources.getResource("compiled/lots-of-args.wat.wasm"));
        var instance =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();

        var function = instance.export("test");
        long[] result = function.apply(2, 3);
        assertArrayEquals(new long[] {5}, result);
    }
}
