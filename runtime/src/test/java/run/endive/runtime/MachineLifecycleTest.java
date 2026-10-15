package run.endive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.wasm.Parser;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.WasmModule;

/** A failed build never returns its machine to anyone who could close it, so it abandons it. */
public class MachineLifecycleTest {

    private static final class RecordingMachine implements Machine {
        private final Machine delegate;
        int abandoned;
        int closed;

        RecordingMachine(Instance instance) {
            this.delegate = new InterpreterMachine(instance);
        }

        @Override
        public long[] call(int funcId, long[] args) {
            return delegate.call(funcId, args);
        }

        @Override
        public void close() {
            closed++;
        }

        @Override
        public void abandon() {
            abandoned++;
        }
    }

    @Test
    public void aFailedBuildAbandonsItsMachine() {
        RecordingMachine[] machine = new RecordingMachine[1];
        var builder =
                Instance.builder(load("compiled/trap.wat.wasm"))
                        .withMachineFactory(
                                instance -> {
                                    machine[0] = new RecordingMachine(instance);
                                    return machine[0];
                                });

        assertThrows(UninstantiableException.class, builder::build);
        assertEquals(1, machine[0].abandoned);
        assertEquals(0, machine[0].closed);
    }

    @Test
    public void aSuccessfulBuildKeepsItsMachine() {
        RecordingMachine[] machine = new RecordingMachine[1];
        var instance =
                Instance.builder(load("compiled/add.wat.wasm"))
                        .withMachineFactory(
                                i -> {
                                    machine[0] = new RecordingMachine(i);
                                    return machine[0];
                                })
                        .build();

        assertEquals(0, machine[0].abandoned);
        instance.close();
        assertEquals(1, machine[0].closed);
    }

    private static WasmModule load(String name) {
        return Parser.parse(CorpusResources.getResource(name));
    }
}
