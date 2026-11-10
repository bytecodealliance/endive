package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/** Tail calls that resolve through the reference-typed call path. */
public class TailCallRefsTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/tail_call_refs.wat.wasm"));

    private static Stream<Arguments> machines() {
        return Stream.of(
                Arguments.of("interpreter", (Function<Instance, Machine>) InterpreterMachine::new),
                Arguments.of(
                        "compiler", (Function<Instance, Machine>) MachineFactoryCompiler::compile));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void tailCallsWithRefArgs(String machine, Function<Instance, Machine> factory) {
        var instance = Instance.builder(MODULE).withMachineFactory(factory).build();
        var result =
                instance.export("count")
                        .applyWithRefs(new long[] {0, 3}, new Object[] {null, null});
        assertEquals(42L, result.longs()[0]);
        // no tail call left pending for the next call to pick up
        assertEquals(7L, instance.export("plain").apply()[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void tailCallChainReturningRef(String machine, Function<Instance, Machine> factory) {
        var instance = Instance.builder(MODULE).withMachineFactory(factory).build();
        assertEquals(42L, instance.export("chain").apply()[0]);
    }
}
