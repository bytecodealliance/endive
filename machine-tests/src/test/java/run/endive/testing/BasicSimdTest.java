package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static run.endive.wasm.types.Value.i16ToVec;
import static run.endive.wasm.types.Value.i32ToVec;
import static run.endive.wasm.types.Value.i8ToVec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.Parser;

public class BasicSimdTest {

    private static Stream<Arguments> machines() {
        return Stream.of(
                Arguments.of("interpreter", (Function<Instance, Machine>) InterpreterMachine::new),
                Arguments.of(
                        "compiler", (Function<Instance, Machine>) MachineFactoryCompiler::compile));
    }

    private static Instance instance(String wasm, Function<Instance, Machine> machine) {
        return Instance.builder(Parser.parse(CorpusResources.getResource(wasm)))
                .withMachineFactory(machine)
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void shouldRunBasicExample(String engine, Function<Instance, Machine> machine) {
        // from: https://blog.dkwr.de/development/wasm-simd-operations/
        var instance = instance("compiled/simd-example.wat.wasm", machine);
        assertEquals(6L, instance.export("main").apply()[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void shouldRoundTripV128Locals(String engine, Function<Instance, Machine> machine) {
        var instance = instance("compiled/simd-locals.wat.wasm", machine);
        assertEquals(10L, instance.export("local_roundtrip").apply()[0]);
        assertEquals(7L, instance.export("local_roundtrip_lane0").apply()[0]);
        assertEquals(10L, instance.export("local_tee").apply()[0]);
        assertEquals(7L, instance.export("local_tee_get").apply()[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void shouldTrapOnStoreEffectiveAddressOverflow(
            String engine, Function<Instance, Machine> machine) {
        var instance = instance("compiled/simd-store-offset-wrap.wat.wasm", machine);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void shouldPairLanesWithDistinctValues(
            String engine, Function<Instance, Machine> machine) {
        var instance = instance("compiled/simd-mixed-lanes.wat.wasm", machine);

        assertArrayEquals(
                i32ToVec(new long[] {50, 250, 610, -150}),
                apply(
                        instance,
                        "i32x4.dot_i16x8_s",
                        i16ToVec(new long[] {1, 2, 3, 4, 5, 6, 7, -8}),
                        i16ToVec(new long[] {10, 20, 30, 40, 50, 60, 70, 80})));

        var bytes = i8ToVec(new long[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -1, -128});
        assertArrayEquals(
                i16ToVec(new long[] {3, 7, 11, 15, 19, 23, 27, -129}),
                apply(instance, "i16x8.extadd_pairwise_i8x16_s", bytes));
        assertArrayEquals(
                i16ToVec(new long[] {3, 7, 11, 15, 19, 23, 27, 383}),
                apply(instance, "i16x8.extadd_pairwise_i8x16_u", bytes));

        var shorts = i16ToVec(new long[] {1, 2, 3, 4, 5, 6, -1, -32768});
        assertArrayEquals(
                i32ToVec(new long[] {3, 7, 11, -32769}),
                apply(instance, "i32x4.extadd_pairwise_i16x8_s", shorts));
        assertArrayEquals(
                i32ToVec(new long[] {3, 7, 11, 98303}),
                apply(instance, "i32x4.extadd_pairwise_i16x8_u", shorts));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void shouldSelectExtmulHalvesWithDistinctValues(
            String engine, Function<Instance, Machine> machine) {
        var instance = instance("compiled/simd-mixed-lanes.wat.wasm", machine);

        var bytesA = i8ToVec(new long[] {1, 2, 3, 4, 5, 6, 7, -8, 9, 10, 11, 12, 13, 14, 15, -16});
        var bytesB = i8ToVec(new long[] {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17});
        assertArrayEquals(
                i16ToVec(new long[] {2, 6, 12, 20, 30, 42, 56, -72}),
                apply(instance, "i16x8.extmul_low_i8x16_s", bytesA, bytesB));
        assertArrayEquals(
                i16ToVec(new long[] {90, 110, 132, 156, 182, 210, 240, 4080}),
                apply(instance, "i16x8.extmul_high_i8x16_u", bytesA, bytesB));

        var shortsA = i16ToVec(new long[] {1, 2, 3, -4, 5, 6, 7, -8});
        var shortsB = i16ToVec(new long[] {10, 20, 30, 40, 50, 60, 70, 80});
        assertArrayEquals(
                i32ToVec(new long[] {10, 40, 90, 2621280}),
                apply(instance, "i32x4.extmul_low_i16x8_u", shortsA, shortsB));
        assertArrayEquals(
                i32ToVec(new long[] {250, 360, 490, -640}),
                apply(instance, "i32x4.extmul_high_i16x8_s", shortsA, shortsB));

        var intsA = i32ToVec(new long[] {3, -4, 5, -6});
        var intsB = i32ToVec(new long[] {7, 8, 9, 10});
        assertArrayEquals(
                new long[] {21, -32}, apply(instance, "i64x2.extmul_low_i32x4_s", intsA, intsB));
        assertArrayEquals(
                new long[] {45, 42949672900L},
                apply(instance, "i64x2.extmul_high_i32x4_u", intsA, intsB));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machines")
    public void shouldBoundsCheckWholeAccessAtEndOfMemory(
            String engine, Function<Instance, Machine> machine) {
        var instance = instance("compiled/simd-memory-bounds.wat.wasm", machine);
        var memory = instance.memory();
        int memorySize = 65536;
        // export suffix -> access width in bytes, narrowest first
        var accesses =
                List.of(
                        Map.entry("8_lane", 1),
                        Map.entry("16_lane", 2),
                        Map.entry("32_lane", 4),
                        Map.entry("64_lane", 8),
                        Map.entry("", 16));

        for (var access : accesses) {
            long pastEnd = memorySize - access.getValue() + 1L;
            for (var name :
                    List.of("v128.load" + access.getKey(), "v128.store" + access.getKey())) {
                var function = instance.export(name);
                assertThrows(WasmRuntimeException.class, () -> function.apply(pastEnd), name);
            }
        }
        assertArrayEquals(new byte[16], memory.readBytes(memorySize - 16, 16));

        for (var access : accesses) {
            int width = access.getValue();
            int lastValid = memorySize - width;
            instance.export("v128.store" + access.getKey()).apply(lastValid);

            var tail = new byte[16];
            Arrays.fill(tail, 16 - width, 16, (byte) -1);
            assertArrayEquals(tail, memory.readBytes(memorySize - 16, 16), access.getKey());
            long lane0 = width >= 8 ? -1L : (1L << (8 * width)) - 1;
            assertEquals(
                    lane0,
                    instance.export("v128.load" + access.getKey()).apply(lastValid)[0],
                    access.getKey());
        }
    }

    private static long[] apply(Instance instance, String name, long[]... vectors) {
        var args = Stream.of(vectors).flatMapToLong(LongStream::of).toArray();
        return instance.export(name).apply(args);
    }
}
