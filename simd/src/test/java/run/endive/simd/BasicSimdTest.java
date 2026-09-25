package run.endive.simd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.Parser;

public class BasicSimdTest {

    @Test
    public void shouldRunBasicExample() {
        // from: https://blog.dkwr.de/development/wasm-simd-operations/
        var instance =
                Instance.builder(
                                Parser.parse(
                                        CorpusResources.getResource(
                                                "compiled/simd-example.wat.wasm")))
                        .withMachineFactory(SimdInterpreterMachine::new)
                        .build();
        var main = instance.export("main");
        var result = main.apply()[0];
        assertEquals(6L, result);
    }

    @Test
    public void shouldRoundTripV128Locals() {
        var instance =
                Instance.builder(
                                Parser.parse(
                                        CorpusResources.getResource(
                                                "compiled/simd-locals.wat.wasm")))
                        .withMachineFactory(SimdInterpreterMachine::new)
                        .build();
        assertEquals(10L, instance.export("local_roundtrip").apply()[0]);
        assertEquals(7L, instance.export("local_roundtrip_lane0").apply()[0]);
        assertEquals(10L, instance.export("local_tee").apply()[0]);
        assertEquals(7L, instance.export("local_tee_get").apply()[0]);
    }

    @Test
    public void shouldTrapOnStoreEffectiveAddressOverflow() {
        var instance =
                Instance.builder(
                                Parser.parse(
                                        CorpusResources.getResource(
                                                "compiled/simd-store-offset-wrap.wat.wasm")))
                        .withMachineFactory(SimdInterpreterMachine::new)
                        .build();
        var memory = instance.memory();

        for (var name : List.of("v128_store", "v128_store8_lane")) {
            var store = instance.export(name);
            assertThrows(WasmRuntimeException.class, () -> store.apply(-1L), name);
            assertThrows(WasmRuntimeException.class, () -> store.apply(0xFFFFFFFFL), name);
        }
        for (var name : List.of("v128_store_max_offset", "v128_store8_lane_max_offset")) {
            var store = instance.export(name);
            assertThrows(WasmRuntimeException.class, () -> store.apply(1L), name);
        }
        assertArrayEquals(new byte[16], memory.readBytes(0, 16));
    }
}
