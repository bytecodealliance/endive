package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.wasm.Parser;

class V128LocalsTest {

    @Test
    void shouldRoundTripV128Locals() {
        var instance =
                Instance.builder(
                                Parser.parse(
                                        CorpusResources.getResource(
                                                "compiled/simd-locals.wat.wasm")))
                        .withMachineFactory(InterpreterMachine::new)
                        .build();
        assertEquals(10L, instance.export("local_roundtrip").apply()[0]);
        assertEquals(7L, instance.export("local_roundtrip_lane0").apply()[0]);
        assertEquals(10L, instance.export("local_tee").apply()[0]);
        assertEquals(7L, instance.export("local_tee_get").apply()[0]);
    }
}
