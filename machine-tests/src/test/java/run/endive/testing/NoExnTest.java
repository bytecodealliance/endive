package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/** Tests for the `noexn` bottom type of the exception hierarchy. */
public class NoExnTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/noexn.wat.wasm"));

    private static Stream<Arguments> machineImplementations() {
        return Stream.of(
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(InterpreterMachine::new)),
                Arguments.of(
                        (Function<Instance.Builder, Instance.Builder>)
                                (b) -> b.withMachineFactory(MachineFactoryCompiler::compile)));
    }

    private static Instance instance(Function<Instance.Builder, Instance.Builder> machineInject) {
        return machineInject
                .apply(Instance.builder(MODULE).withImportValues(ImportValues.builder().build()))
                .build();
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void nullToExnRef(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        var result = instance.export("null-to-exnref").applyWithRefs(new long[0], new Object[0]);
        assertNull(result.refResult(0));
    }

    /** An exnref signature is an object-ref signature, so the flat path is rejected. */
    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void exnRefRejectsApply(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertThrows(
                UnsupportedOperationException.class,
                () -> instance.export("null-to-exnref").apply());
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void isNull(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(1, instance.export("is-null").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void roundtrip(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(1, instance.export("roundtrip").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void fromGlobal(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(1, instance.export("from-global").apply()[0]);
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    public void fromTable(Function<Instance.Builder, Instance.Builder> machineInject) {
        var instance = instance(machineInject);
        assertEquals(1, instance.export("from-table").apply()[0]);
    }
}
