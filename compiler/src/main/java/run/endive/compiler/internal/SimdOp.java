package run.endive.compiler.internal;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.OpCode;
import run.endive.wasm.types.ValType;

/** The stack shape and calling convention of every v128 opcode. */
final class SimdOp {

    enum Kind {
        LOAD,
        LOAD_LANE,
        STORE,
        STORE_LANE,
        CONST,
        SHUFFLE,
        SPLAT,
        EXTRACT,
        REPLACE,
        // one result word from the matching word of each operand
        UNARY,
        BINARY,
        TERNARY,
        SHIFT,
        // a result word from one half of the operands: the low or high word, see half
        UNARY_HALF,
        BINARY_HALF,
        // the low result word from both operand words, a zero high word
        UNARY_ZERO,
        // each result word from both words of one operand
        NARROW,
        // each result word from both words of the first operand and a word of the second
        SWIZZLE,
        // [v128] -> [i32]
        TEST
    }

    private static final Map<OpCode, SimdOp> OPS = new EnumMap<>(OpCode.class);

    final OpCode op;
    final Kind kind;
    // the lane type of splat, extract and replace
    final ValType scalar;
    final int lanesPerWord;
    // the operand word a HALF op reads: 0 low, 1 high
    final int half;

    private SimdOp(OpCode op, Kind kind, ValType scalar, int lanesPerWord, int half) {
        this.op = op;
        this.kind = kind;
        this.scalar = scalar;
        this.lanesPerWord = lanesPerWord;
        this.half = half;
    }

    static boolean isV128(OpCode op) {
        return op.opcode() >= 0xFD00 && op.opcode() <= 0xFDFF;
    }

    // the op of a V128_OP instruction, whose last operand is the wasm opcode
    static SimdOp of(CompilerInstruction ins) {
        return of(OpCode.byOpCode((int) ins.operand(ins.operandCount() - 1)));
    }

    static SimdOp of(OpCode op) {
        var simd = OPS.get(op);
        if (simd == null) {
            throw new WasmEngineException("v128 opcode not supported by the compiler: " + op);
        }
        return simd;
    }

    static Iterable<SimdOp> all() {
        return OPS.values();
    }

    private static void add(Kind kind, OpCode... ops) {
        for (var op : ops) {
            put(new SimdOp(op, kind, null, 0, 0));
        }
    }

    private static void half(Kind kind, int half, OpCode... ops) {
        for (var op : ops) {
            put(new SimdOp(op, kind, null, 0, half));
        }
    }

    private static void lanes(ValType scalar, int lanesPerWord, OpCode splat, OpCode... lanes) {
        put(new SimdOp(splat, Kind.SPLAT, scalar, lanesPerWord, 0));
        for (var op : lanes) {
            var kind = op.name().endsWith("REPLACE_LANE") ? Kind.REPLACE : Kind.EXTRACT;
            put(new SimdOp(op, kind, scalar, lanesPerWord, 0));
        }
    }

    private static void put(SimdOp simd) {
        if (OPS.put(simd.op, simd) != null) {
            throw new IllegalStateException("v128 opcode listed twice: " + simd.op);
        }
    }

    static {
        add(Kind.CONST, OpCode.V128_CONST);
        add(Kind.SHUFFLE, OpCode.I8x16_SHUFFLE);
        add(
                Kind.LOAD,
                OpCode.V128_LOAD,
                OpCode.V128_LOAD8x8_S,
                OpCode.V128_LOAD8x8_U,
                OpCode.V128_LOAD16x4_S,
                OpCode.V128_LOAD16x4_U,
                OpCode.V128_LOAD32x2_S,
                OpCode.V128_LOAD32x2_U,
                OpCode.V128_LOAD8_SPLAT,
                OpCode.V128_LOAD16_SPLAT,
                OpCode.V128_LOAD32_SPLAT,
                OpCode.V128_LOAD64_SPLAT,
                OpCode.V128_LOAD32_ZERO,
                OpCode.V128_LOAD64_ZERO);
        add(
                Kind.LOAD_LANE,
                OpCode.V128_LOAD8_LANE,
                OpCode.V128_LOAD16_LANE,
                OpCode.V128_LOAD32_LANE,
                OpCode.V128_LOAD64_LANE);
        add(Kind.STORE, OpCode.V128_STORE);
        add(
                Kind.STORE_LANE,
                OpCode.V128_STORE8_LANE,
                OpCode.V128_STORE16_LANE,
                OpCode.V128_STORE32_LANE,
                OpCode.V128_STORE64_LANE);

        lanes(
                ValType.I32,
                8,
                OpCode.I8x16_SPLAT,
                OpCode.I8x16_EXTRACT_LANE_S,
                OpCode.I8x16_EXTRACT_LANE_U,
                OpCode.I8x16_REPLACE_LANE);
        lanes(
                ValType.I32,
                4,
                OpCode.I16x8_SPLAT,
                OpCode.I16x8_EXTRACT_LANE_S,
                OpCode.I16x8_EXTRACT_LANE_U,
                OpCode.I16x8_REPLACE_LANE);
        lanes(
                ValType.I32,
                2,
                OpCode.I32x4_SPLAT,
                OpCode.I32x4_EXTRACT_LANE,
                OpCode.I32x4_REPLACE_LANE);
        lanes(
                ValType.I64,
                1,
                OpCode.I64x2_SPLAT,
                OpCode.I64x2_EXTRACT_LANE,
                OpCode.I64x2_REPLACE_LANE);
        lanes(
                ValType.F32,
                2,
                OpCode.F32x4_SPLAT,
                OpCode.F32x4_EXTRACT_LANE,
                OpCode.F32x4_REPLACE_LANE);
        lanes(
                ValType.F64,
                1,
                OpCode.F64x2_SPLAT,
                OpCode.F64x2_EXTRACT_LANE,
                OpCode.F64x2_REPLACE_LANE);

        add(
                Kind.UNARY,
                OpCode.V128_NOT,
                OpCode.I8x16_ABS,
                OpCode.I8x16_NEG,
                OpCode.I8x16_POPCNT,
                OpCode.I16x8_ABS,
                OpCode.I16x8_NEG,
                OpCode.I16x8_EXTADD_PAIRWISE_I8x16_S,
                OpCode.I16x8_EXTADD_PAIRWISE_I8x16_U,
                OpCode.I32x4_ABS,
                OpCode.I32x4_NEG,
                OpCode.I32x4_EXTADD_PAIRWISE_I16x8_S,
                OpCode.I32x4_EXTADD_PAIRWISE_I16x8_U,
                OpCode.I32x4_TRUNC_SAT_F32X4_S,
                OpCode.I32x4_TRUNC_SAT_F32X4_U,
                OpCode.I64x2_ABS,
                OpCode.I64x2_NEG,
                OpCode.F32x4_ABS,
                OpCode.F32x4_NEG,
                OpCode.F32x4_SQRT,
                OpCode.F32x4_CEIL,
                OpCode.F32x4_FLOOR,
                OpCode.F32x4_TRUNC,
                OpCode.F32x4_NEAREST,
                OpCode.F32x4_CONVERT_I32x4_S,
                OpCode.F32x4_CONVERT_I32x4_U,
                OpCode.F64x2_ABS,
                OpCode.F64x2_NEG,
                OpCode.F64x2_SQRT,
                OpCode.F64x2_CEIL,
                OpCode.F64x2_FLOOR,
                OpCode.F64x2_TRUNC,
                OpCode.F64x2_NEAREST);
        half(
                Kind.UNARY_HALF,
                0,
                OpCode.I16x8_EXTEND_LOW_I8x16_S,
                OpCode.I16x8_EXTEND_LOW_I8x16_U,
                OpCode.I32x4_EXTEND_LOW_I16x8_S,
                OpCode.I32x4_EXTEND_LOW_I16x8_U,
                OpCode.I64x2_EXTEND_LOW_I32x4_S,
                OpCode.I64x2_EXTEND_LOW_I32x4_U,
                OpCode.F64x2_PROMOTE_LOW_F32x4,
                OpCode.F64x2_CONVERT_LOW_I32x4_S,
                OpCode.F64x2_CONVERT_LOW_I32x4_U);
        half(
                Kind.UNARY_HALF,
                1,
                OpCode.I16x8_EXTEND_HIGH_I8x16_S,
                OpCode.I16x8_EXTEND_HIGH_I8x16_U,
                OpCode.I32x4_EXTEND_HIGH_I16x8_S,
                OpCode.I32x4_EXTEND_HIGH_I16x8_U,
                OpCode.I64x2_EXTEND_HIGH_I32x4_S,
                OpCode.I64x2_EXTEND_HIGH_I32x4_U);
        add(
                Kind.UNARY_ZERO,
                OpCode.F32x4_DEMOTE_LOW_F64x2_ZERO,
                OpCode.I32x4_TRUNC_SAT_F64x2_S_ZERO,
                OpCode.I32x4_TRUNC_SAT_F64x2_U_ZERO);

        add(
                Kind.BINARY,
                OpCode.V128_AND,
                OpCode.V128_ANDNOT,
                OpCode.V128_OR,
                OpCode.V128_XOR,
                OpCode.I8x16_EQ,
                OpCode.I8x16_NE,
                OpCode.I8x16_LT_S,
                OpCode.I8x16_LT_U,
                OpCode.I8x16_GT_S,
                OpCode.I8x16_GT_U,
                OpCode.I8x16_LE_S,
                OpCode.I8x16_LE_U,
                OpCode.I8x16_GE_S,
                OpCode.I8x16_GE_U,
                OpCode.I8x16_ADD,
                OpCode.I8x16_ADD_SAT_S,
                OpCode.I8x16_ADD_SAT_U,
                OpCode.I8x16_SUB,
                OpCode.I8x16_SUB_SAT_S,
                OpCode.I8x16_SUB_SAT_U,
                OpCode.I8x16_MIN_S,
                OpCode.I8x16_MIN_U,
                OpCode.I8x16_MAX_S,
                OpCode.I8x16_MAX_U,
                OpCode.I8x16_AVGR_U,
                OpCode.I16x8_EQ,
                OpCode.I16x8_NE,
                OpCode.I16x8_LT_S,
                OpCode.I16x8_LT_U,
                OpCode.I16x8_GT_S,
                OpCode.I16x8_GT_U,
                OpCode.I16x8_LE_S,
                OpCode.I16x8_LE_U,
                OpCode.I16x8_GE_S,
                OpCode.I16x8_GE_U,
                OpCode.I16x8_Q15MULR_SAT_S,
                OpCode.I16x8_ADD,
                OpCode.I16x8_ADD_SAT_S,
                OpCode.I16x8_ADD_SAT_U,
                OpCode.I16x8_SUB,
                OpCode.I16x8_SUB_SAT_S,
                OpCode.I16x8_SUB_SAT_U,
                OpCode.I16x8_MUL,
                OpCode.I16x8_MIN_S,
                OpCode.I16x8_MIN_U,
                OpCode.I16x8_MAX_S,
                OpCode.I16x8_MAX_U,
                OpCode.I16x8_AVGR_U,
                OpCode.I32x4_EQ,
                OpCode.I32x4_NE,
                OpCode.I32x4_LT_S,
                OpCode.I32x4_LT_U,
                OpCode.I32x4_GT_S,
                OpCode.I32x4_GT_U,
                OpCode.I32x4_LE_S,
                OpCode.I32x4_LE_U,
                OpCode.I32x4_GE_S,
                OpCode.I32x4_GE_U,
                OpCode.I32x4_ADD,
                OpCode.I32x4_SUB,
                OpCode.I32x4_MUL,
                OpCode.I32x4_MIN_S,
                OpCode.I32x4_MIN_U,
                OpCode.I32x4_MAX_S,
                OpCode.I32x4_MAX_U,
                OpCode.I32x4_DOT_I16x8_S,
                OpCode.I64x2_ADD,
                OpCode.I64x2_SUB,
                OpCode.I64x2_MUL,
                OpCode.I64x2_EQ,
                OpCode.I64x2_NE,
                OpCode.I64x2_LT_S,
                OpCode.I64x2_GT_S,
                OpCode.I64x2_LE_S,
                OpCode.I64x2_GE_S,
                OpCode.F32x4_EQ,
                OpCode.F32x4_NE,
                OpCode.F32x4_LT,
                OpCode.F32x4_GT,
                OpCode.F32x4_LE,
                OpCode.F32x4_GE,
                OpCode.F32x4_ADD,
                OpCode.F32x4_SUB,
                OpCode.F32x4_MUL,
                OpCode.F32x4_DIV,
                OpCode.F32x4_MIN,
                OpCode.F32x4_MAX,
                OpCode.F32x4_PMIN,
                OpCode.F32x4_PMAX,
                OpCode.F64x2_EQ,
                OpCode.F64x2_NE,
                OpCode.F64x2_LT,
                OpCode.F64x2_GT,
                OpCode.F64x2_LE,
                OpCode.F64x2_GE,
                OpCode.F64x2_ADD,
                OpCode.F64x2_SUB,
                OpCode.F64x2_MUL,
                OpCode.F64x2_DIV,
                OpCode.F64x2_MIN,
                OpCode.F64x2_MAX,
                OpCode.F64x2_PMIN,
                OpCode.F64x2_PMAX);
        half(
                Kind.BINARY_HALF,
                0,
                OpCode.I16x8_EXTMUL_LOW_I8x16_S,
                OpCode.I16x8_EXTMUL_LOW_I8x16_U,
                OpCode.I32x4_EXTMUL_LOW_I16x8_S,
                OpCode.I32x4_EXTMUL_LOW_I16x8_U,
                OpCode.I64x2_EXTMUL_LOW_I32x4_S,
                OpCode.I64x2_EXTMUL_LOW_I32x4_U);
        half(
                Kind.BINARY_HALF,
                1,
                OpCode.I16x8_EXTMUL_HIGH_I8x16_S,
                OpCode.I16x8_EXTMUL_HIGH_I8x16_U,
                OpCode.I32x4_EXTMUL_HIGH_I16x8_S,
                OpCode.I32x4_EXTMUL_HIGH_I16x8_U,
                OpCode.I64x2_EXTMUL_HIGH_I32x4_S,
                OpCode.I64x2_EXTMUL_HIGH_I32x4_U);
        add(
                Kind.NARROW,
                OpCode.I8x16_NARROW_I16x8_S,
                OpCode.I8x16_NARROW_I16x8_U,
                OpCode.I16x8_NARROW_I32x4_S,
                OpCode.I16x8_NARROW_I32x4_U);
        add(Kind.SWIZZLE, OpCode.I8x16_SWIZZLE);
        add(Kind.TERNARY, OpCode.V128_BITSELECT);
        add(
                Kind.SHIFT,
                OpCode.I8x16_SHL,
                OpCode.I8x16_SHR_S,
                OpCode.I8x16_SHR_U,
                OpCode.I16x8_SHL,
                OpCode.I16x8_SHR_S,
                OpCode.I16x8_SHR_U,
                OpCode.I32x4_SHL,
                OpCode.I32x4_SHR_S,
                OpCode.I32x4_SHR_U,
                OpCode.I64x2_SHL,
                OpCode.I64x2_SHR_S,
                OpCode.I64x2_SHR_U);
        add(
                Kind.TEST,
                OpCode.V128_ANY_TRUE,
                OpCode.I8x16_ALL_TRUE,
                OpCode.I8x16_BITMASK,
                OpCode.I16x8_ALL_TRUE,
                OpCode.I16x8_BITMASK,
                OpCode.I32x4_ALL_TRUE,
                OpCode.I32x4_BITMASK,
                OpCode.I64x2_ALL_TRUE,
                OpCode.I64x2_BITMASK);
    }

    List<ValType> inputs() {
        switch (kind) {
            case LOAD:
                return List.of(ValType.I32);
            case LOAD_LANE:
            case STORE:
            case STORE_LANE:
                return List.of(ValType.I32, ValType.V128);
            case CONST:
                return List.of();
            case SHUFFLE:
            case BINARY:
            case BINARY_HALF:
            case NARROW:
            case SWIZZLE:
                return List.of(ValType.V128, ValType.V128);
            case SPLAT:
                return List.of(scalar);
            case EXTRACT:
            case UNARY:
            case UNARY_HALF:
            case UNARY_ZERO:
            case TEST:
                return List.of(ValType.V128);
            case REPLACE:
                return List.of(ValType.V128, scalar);
            case TERNARY:
                return List.of(ValType.V128, ValType.V128, ValType.V128);
            case SHIFT:
                return List.of(ValType.V128, ValType.I32);
            default:
                throw new IllegalStateException("no stack shape for " + kind);
        }
    }

    // the result type, or null for stores
    ValType output() {
        switch (kind) {
            case STORE:
            case STORE_LANE:
                return null;
            case EXTRACT:
                return scalar;
            case TEST:
                return ValType.I32;
            default:
                return ValType.V128;
        }
    }
}
