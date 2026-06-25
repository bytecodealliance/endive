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
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

public class GcStressTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/gc_stress.wat.wasm"));

    private static Stream<Arguments> machineImplementations() {
        return Stream.of(
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(InterpreterMachine::new)),
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(MachineFactoryCompiler::compile)));
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void gcCollectsUnreachableStructs(
            Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = machineInject.apply(Instance.builder(MODULE)).build();
        var allocateChain = instance.export("allocate_chain");

        // Allocate chains of 10,000 structs, 100 times.
        // Each call creates a new chain; the previous chain becomes garbage.
        // If Java GC doesn't collect unreachable Wasm GC refs, this OOMs.
        for (int i = 0; i < 100; i++) {
            var result = allocateChain.apply(10_000);
            // Last struct in chain has val = n-1
            assertEquals(9999L, result[0]);
        }
    }
}
