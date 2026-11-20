package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.WatGenerator;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;

/** Each v128 compute op, compiled, against the interpreter on edge and random operands. */
public class SimdOpDifferentialTest {

    private static final List<String> BINARY =
            List.of(
                    "v128.and",
                    "v128.andnot",
                    "v128.or",
                    "v128.xor",
                    "i8x16.swizzle",
                    "i8x16.eq",
                    "i8x16.ne",
                    "i8x16.lt_s",
                    "i8x16.lt_u",
                    "i8x16.gt_s",
                    "i8x16.gt_u",
                    "i8x16.le_s",
                    "i8x16.le_u",
                    "i8x16.ge_s",
                    "i8x16.ge_u",
                    "i8x16.narrow_i16x8_s",
                    "i8x16.narrow_i16x8_u",
                    "i8x16.add",
                    "i8x16.add_sat_s",
                    "i8x16.add_sat_u",
                    "i8x16.sub",
                    "i8x16.sub_sat_s",
                    "i8x16.sub_sat_u",
                    "i8x16.min_s",
                    "i8x16.min_u",
                    "i8x16.max_s",
                    "i8x16.max_u",
                    "i8x16.avgr_u",
                    "i16x8.eq",
                    "i16x8.ne",
                    "i16x8.lt_s",
                    "i16x8.lt_u",
                    "i16x8.gt_s",
                    "i16x8.gt_u",
                    "i16x8.le_s",
                    "i16x8.le_u",
                    "i16x8.ge_s",
                    "i16x8.ge_u",
                    "i16x8.q15mulr_sat_s",
                    "i16x8.narrow_i32x4_s",
                    "i16x8.narrow_i32x4_u",
                    "i16x8.add",
                    "i16x8.add_sat_s",
                    "i16x8.add_sat_u",
                    "i16x8.sub",
                    "i16x8.sub_sat_s",
                    "i16x8.sub_sat_u",
                    "i16x8.mul",
                    "i16x8.min_s",
                    "i16x8.min_u",
                    "i16x8.max_s",
                    "i16x8.max_u",
                    "i16x8.avgr_u",
                    "i16x8.extmul_low_i8x16_s",
                    "i16x8.extmul_high_i8x16_s",
                    "i16x8.extmul_low_i8x16_u",
                    "i16x8.extmul_high_i8x16_u",
                    "i32x4.eq",
                    "i32x4.ne",
                    "i32x4.lt_s",
                    "i32x4.lt_u",
                    "i32x4.gt_s",
                    "i32x4.gt_u",
                    "i32x4.le_s",
                    "i32x4.le_u",
                    "i32x4.ge_s",
                    "i32x4.ge_u",
                    "i32x4.add",
                    "i32x4.sub",
                    "i32x4.mul",
                    "i32x4.min_s",
                    "i32x4.min_u",
                    "i32x4.max_s",
                    "i32x4.max_u",
                    "i32x4.dot_i16x8_s",
                    "i32x4.extmul_low_i16x8_s",
                    "i32x4.extmul_high_i16x8_s",
                    "i32x4.extmul_low_i16x8_u",
                    "i32x4.extmul_high_i16x8_u",
                    "i64x2.add",
                    "i64x2.sub",
                    "i64x2.mul",
                    "i64x2.eq",
                    "i64x2.ne",
                    "i64x2.lt_s",
                    "i64x2.gt_s",
                    "i64x2.le_s",
                    "i64x2.ge_s",
                    "i64x2.extmul_low_i32x4_s",
                    "i64x2.extmul_high_i32x4_s",
                    "i64x2.extmul_low_i32x4_u",
                    "i64x2.extmul_high_i32x4_u",
                    "f32x4.eq",
                    "f32x4.ne",
                    "f32x4.lt",
                    "f32x4.gt",
                    "f32x4.le",
                    "f32x4.ge",
                    "f32x4.add",
                    "f32x4.sub",
                    "f32x4.mul",
                    "f32x4.div",
                    "f32x4.min",
                    "f32x4.max",
                    "f32x4.pmin",
                    "f32x4.pmax",
                    "f64x2.eq",
                    "f64x2.ne",
                    "f64x2.lt",
                    "f64x2.gt",
                    "f64x2.le",
                    "f64x2.ge",
                    "f64x2.add",
                    "f64x2.sub",
                    "f64x2.mul",
                    "f64x2.div",
                    "f64x2.min",
                    "f64x2.max",
                    "f64x2.pmin",
                    "f64x2.pmax");

    private static final List<String> UNARY =
            List.of(
                    "v128.not",
                    "i8x16.abs",
                    "i8x16.neg",
                    "i8x16.popcnt",
                    "i16x8.abs",
                    "i16x8.neg",
                    "i16x8.extadd_pairwise_i8x16_s",
                    "i16x8.extadd_pairwise_i8x16_u",
                    "i16x8.extend_low_i8x16_s",
                    "i16x8.extend_high_i8x16_s",
                    "i16x8.extend_low_i8x16_u",
                    "i16x8.extend_high_i8x16_u",
                    "i32x4.abs",
                    "i32x4.neg",
                    "i32x4.extadd_pairwise_i16x8_s",
                    "i32x4.extadd_pairwise_i16x8_u",
                    "i32x4.extend_low_i16x8_s",
                    "i32x4.extend_high_i16x8_s",
                    "i32x4.extend_low_i16x8_u",
                    "i32x4.extend_high_i16x8_u",
                    "i32x4.trunc_sat_f32x4_s",
                    "i32x4.trunc_sat_f32x4_u",
                    "i32x4.trunc_sat_f64x2_s_zero",
                    "i32x4.trunc_sat_f64x2_u_zero",
                    "i64x2.abs",
                    "i64x2.neg",
                    "i64x2.extend_low_i32x4_s",
                    "i64x2.extend_high_i32x4_s",
                    "i64x2.extend_low_i32x4_u",
                    "i64x2.extend_high_i32x4_u",
                    "f32x4.abs",
                    "f32x4.neg",
                    "f32x4.sqrt",
                    "f32x4.ceil",
                    "f32x4.floor",
                    "f32x4.trunc",
                    "f32x4.nearest",
                    "f32x4.convert_i32x4_s",
                    "f32x4.convert_i32x4_u",
                    "f32x4.demote_f64x2_zero",
                    "f64x2.abs",
                    "f64x2.neg",
                    "f64x2.sqrt",
                    "f64x2.ceil",
                    "f64x2.floor",
                    "f64x2.trunc",
                    "f64x2.nearest",
                    "f64x2.promote_low_f32x4",
                    "f64x2.convert_low_i32x4_s",
                    "f64x2.convert_low_i32x4_u");

    private static final List<String> SHIFT =
            List.of(
                    "i8x16.shl",
                    "i8x16.shr_s",
                    "i8x16.shr_u",
                    "i16x8.shl",
                    "i16x8.shr_s",
                    "i16x8.shr_u",
                    "i32x4.shl",
                    "i32x4.shr_s",
                    "i32x4.shr_u",
                    "i64x2.shl",
                    "i64x2.shr_s",
                    "i64x2.shr_u");

    private static final List<String> TEST =
            List.of(
                    "v128.any_true",
                    "i8x16.all_true",
                    "i8x16.bitmask",
                    "i16x8.all_true",
                    "i16x8.bitmask",
                    "i32x4.all_true",
                    "i32x4.bitmask",
                    "i64x2.all_true",
                    "i64x2.bitmask");

    static Stream<Arguments> ops() {
        var ops = new ArrayList<Arguments>();
        BINARY.forEach(op -> ops.add(Arguments.of(op, "binary")));
        UNARY.forEach(op -> ops.add(Arguments.of(op, "unary")));
        SHIFT.forEach(op -> ops.add(Arguments.of(op, "shift")));
        TEST.forEach(op -> ops.add(Arguments.of(op, "test")));
        ops.add(Arguments.of("v128.bitselect", "ternary"));
        return ops.stream();
    }

    private static final long[] WORDS = {
        0,
        -1,
        0x8080808080808080L,
        0x7F7F7F7F7F7F7F7FL,
        0x0101010101010101L,
        0x8000800080008000L,
        0x7FFF7FFF7FFF7FFFL,
        0x8000000080000000L,
        0x7FFFFFFF7FFFFFFFL,
        0xFF00FF0080017F80L,
        0x0010000F00FF0011L,
        // f32 pairs: NaN and -0, inf and 2^31, 4.5e9 and -1.5, 0.5 and 2.5
        0x800000007FC00000L,
        0x4F0000007F800000L,
        0xBFC000004F861C46L,
        0x402000003F000000L,
        // f64: NaN, -0, -inf, 2.5, 1e300
        0x7FF8000000000001L,
        0x8000000000000000L,
        0xFFF0000000000000L,
        0x4004000000000000L,
        0x7E37E43C8800759CL
    };

    @ParameterizedTest(name = "{0}")
    @MethodSource("ops")
    public void sameAsInterpreter(String op, String kind) {
        var module = Parser.parse(Wat2Wasm.parse(WatGenerator.simdOp(op, kind)));
        var interpreter = Instance.builder(module).build().export("run");
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build()
                        .export("run");
        var random = new Random(op.hashCode());
        var cases = new ArrayList<long[]>();
        for (long a : WORDS) {
            for (long b : WORDS) {
                cases.add(new long[] {a, b, b, a, a ^ b, b, random.nextInt(140) - 6});
            }
        }
        for (int i = 0; i < 2000; i++) {
            long a = random.nextLong();
            long b = random.nextInt(3) == 0 ? a ^ (1L << random.nextInt(64)) : random.nextLong();
            cases.add(
                    new long[] {
                        a,
                        random.nextLong(),
                        b,
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextInt()
                    });
        }
        // float arithmetic may return any NaN; compares, abs, neg, pmin and pmax keep exact bits
        boolean anyNaN =
                op.matches("f(32x4|64x2)\\..*")
                        && !op.matches(".*\\.(eq|ne|lt|gt|le|ge|abs|neg|pmin|pmax)");
        int lanes = !anyNaN ? 0 : op.startsWith("f32x4") ? 32 : 64;
        for (var c : cases) {
            long[] expected = interpreter.apply(c);
            long[] actual = compiled.apply(c);
            for (int w = 0; w < 2; w++) {
                assertTrue(
                        same(expected[w], actual[w], lanes),
                        () ->
                                String.format(
                                        "%s(%016x %016x, %016x %016x, %016x %016x, %d):"
                                                + " expected %016x %016x, got %016x %016x",
                                        op,
                                        c[0],
                                        c[1],
                                        c[2],
                                        c[3],
                                        c[4],
                                        c[5],
                                        c[6],
                                        expected[0],
                                        expected[1],
                                        actual[0],
                                        actual[1]));
            }
        }
    }

    // float lanes may hold any NaN
    private static boolean same(long x, long y, int lanes) {
        if (x == y) {
            return true;
        }
        if (lanes == 64) {
            return Double.isNaN(Double.longBitsToDouble(x))
                    && Double.isNaN(Double.longBitsToDouble(y));
        }
        if (lanes == 32) {
            for (int i = 0; i < 2; i++) {
                int a = (int) (x >>> (32 * i));
                int b = (int) (y >>> (32 * i));
                if (a != b
                        && !(Float.isNaN(Float.intBitsToFloat(a))
                                && Float.isNaN(Float.intBitsToFloat(b)))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
