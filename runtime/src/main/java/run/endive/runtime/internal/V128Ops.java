package run.endive.runtime.internal;

import run.endive.runtime.BitOps;
import run.endive.runtime.Instance;
import run.endive.runtime.MStack;
import run.endive.runtime.OpcodeImpl;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.types.Instruction;

/**
 * Scalar implementation of the WebAssembly SIMD instruction set.
 *
 * <p>Vectors use two stack longs: the low word is pushed first, and lane 0 occupies the low bits
 * of the low word. Lane memory operands are offset = operand(1), memory index = operand(2), and
 * lane index = operand(3); extract/replace use operand(0). Float abs/neg/pmin/pmax preserve
 * operand bits, while float arithmetic canonicalizes NaN results.
 */
public final class V128Ops {
    // binaryInt / binaryFloat
    private static final int ADD = 1;
    private static final int SUB = 2;
    private static final int MUL = 3;
    private static final int MIN_S = 4;
    private static final int MIN_U = 5;
    private static final int MAX_S = 6;
    private static final int MAX_U = 7;
    private static final int EQ = 8;
    private static final int NE = 9;
    private static final int LT_S = 10;
    private static final int LT_U = 11;
    private static final int GT_S = 12;
    private static final int GT_U = 13;
    private static final int LE_S = 14;
    private static final int LE_U = 15;
    private static final int GE_S = 16;
    private static final int GE_U = 17;
    private static final int ADD_SAT_S = 18;
    private static final int ADD_SAT_U = 19;
    private static final int SUB_SAT_S = 20;
    private static final int SUB_SAT_U = 21;
    private static final int AVGR_U = 22;
    private static final int Q15MULR_SAT_S = 23;
    private static final int DIV = 24;
    private static final int PMIN = 25;
    private static final int PMAX = 26;
    private static final int MIN = 27;
    private static final int MAX = 28;
    private static final int LT = 29;
    private static final int LE = 30;
    private static final int GT = 31;
    private static final int GE = 32;

    // bitwise
    private static final int AND = 100;
    private static final int ANDNOT = 101;
    private static final int OR = 102;
    private static final int XOR = 103;

    // shift
    private static final int SHL = 200;
    private static final int SHR_S = 201;
    private static final int SHR_U = 202;

    // unaryInt / unaryFloat
    private static final int ABS = 300;
    private static final int NEG = 301;
    private static final int POPCNT = 302;
    private static final int SQRT = 303;
    private static final int CEIL = 304;
    private static final int FLOOR = 305;
    private static final int TRUNC = 306;
    private static final int NEAREST = 307;

    private V128Ops() {}

    public static boolean eval(MStack stack, Instance instance, Instruction instruction) {
        switch (instruction.opcode()) {
            case V128_LOAD:
                V128_LOAD(stack, instance, instruction);
                break;
            case V128_LOAD8x8_S:
                V128_LOAD8X8_S(stack, instance, instruction);
                break;
            case V128_LOAD8x8_U:
                V128_LOAD8X8_U(stack, instance, instruction);
                break;
            case V128_LOAD16x4_S:
                V128_LOAD16X4_S(stack, instance, instruction);
                break;
            case V128_LOAD16x4_U:
                V128_LOAD16X4_U(stack, instance, instruction);
                break;
            case V128_LOAD32x2_S:
                V128_LOAD32X2_S(stack, instance, instruction);
                break;
            case V128_LOAD32x2_U:
                V128_LOAD32X2_U(stack, instance, instruction);
                break;
            case V128_LOAD8_SPLAT:
                V128_LOAD8_SPLAT(stack, instance, instruction);
                break;
            case V128_LOAD16_SPLAT:
                V128_LOAD16_SPLAT(stack, instance, instruction);
                break;
            case V128_LOAD32_SPLAT:
                V128_LOAD32_SPLAT(stack, instance, instruction);
                break;
            case V128_LOAD64_SPLAT:
                V128_LOAD64_SPLAT(stack, instance, instruction);
                break;
            case V128_STORE:
                V128_STORE(stack, instance, instruction);
                break;
            case V128_CONST:
                V128_CONST(stack, instruction);
                break;
            case I8x16_SHUFFLE:
                I8X16_SHUFFLE(stack, instruction);
                break;
            case I8x16_SWIZZLE:
                I8X16_SWIZZLE(stack);
                break;
            case I8x16_SPLAT:
                I8X16_SPLAT(stack);
                break;
            case I16x8_SPLAT:
                I16X8_SPLAT(stack);
                break;
            case I32x4_SPLAT:
                I32X4_SPLAT(stack);
                break;
            case I64x2_SPLAT:
                I64X2_SPLAT(stack);
                break;
            case F32x4_SPLAT:
                F32X4_SPLAT(stack);
                break;
            case F64x2_SPLAT:
                F64X2_SPLAT(stack);
                break;
            case I8x16_EXTRACT_LANE_S:
                I8X16_EXTRACT_LANE_S(stack, instruction);
                break;
            case I8x16_EXTRACT_LANE_U:
                I8X16_EXTRACT_LANE_U(stack, instruction);
                break;
            case I8x16_REPLACE_LANE:
                I8X16_REPLACE_LANE(stack, instruction);
                break;
            case I16x8_EXTRACT_LANE_S:
                I16X8_EXTRACT_LANE_S(stack, instruction);
                break;
            case I16x8_EXTRACT_LANE_U:
                I16X8_EXTRACT_LANE_U(stack, instruction);
                break;
            case I16x8_REPLACE_LANE:
                I16X8_REPLACE_LANE(stack, instruction);
                break;
            case I32x4_EXTRACT_LANE:
                I32X4_EXTRACT_LANE(stack, instruction);
                break;
            case I32x4_REPLACE_LANE:
                I32X4_REPLACE_LANE(stack, instruction);
                break;
            case I64x2_EXTRACT_LANE:
                I64X2_EXTRACT_LANE(stack, instruction);
                break;
            case I64x2_REPLACE_LANE:
                I64X2_REPLACE_LANE(stack, instruction);
                break;
            case F32x4_EXTRACT_LANE:
                F32X4_EXTRACT_LANE(stack, instruction);
                break;
            case F32x4_REPLACE_LANE:
                F32X4_REPLACE_LANE(stack, instruction);
                break;
            case F64x2_EXTRACT_LANE:
                F64X2_EXTRACT_LANE(stack, instruction);
                break;
            case F64x2_REPLACE_LANE:
                F64X2_REPLACE_LANE(stack, instruction);
                break;
            case I8x16_EQ:
                I8X16_EQ(stack);
                break;
            case I8x16_NE:
                I8X16_NE(stack);
                break;
            case I8x16_LT_S:
                I8X16_LT_S(stack);
                break;
            case I8x16_LT_U:
                I8X16_LT_U(stack);
                break;
            case I8x16_GT_S:
                I8X16_GT_S(stack);
                break;
            case I8x16_GT_U:
                I8X16_GT_U(stack);
                break;
            case I8x16_LE_S:
                I8X16_LE_S(stack);
                break;
            case I8x16_LE_U:
                I8X16_LE_U(stack);
                break;
            case I8x16_GE_S:
                I8X16_GE_S(stack);
                break;
            case I8x16_GE_U:
                I8X16_GE_U(stack);
                break;
            case I16x8_EQ:
                I16X8_EQ(stack);
                break;
            case I16x8_NE:
                I16X8_NE(stack);
                break;
            case I16x8_LT_S:
                I16X8_LT_S(stack);
                break;
            case I16x8_LT_U:
                I16X8_LT_U(stack);
                break;
            case I16x8_GT_S:
                I16X8_GT_S(stack);
                break;
            case I16x8_GT_U:
                I16X8_GT_U(stack);
                break;
            case I16x8_LE_S:
                I16X8_LE_S(stack);
                break;
            case I16x8_LE_U:
                I16X8_LE_U(stack);
                break;
            case I16x8_GE_S:
                I16X8_GE_S(stack);
                break;
            case I16x8_GE_U:
                I16X8_GE_U(stack);
                break;
            case I32x4_EQ:
                I32X4_EQ(stack);
                break;
            case I32x4_NE:
                I32X4_NE(stack);
                break;
            case I32x4_LT_S:
                I32X4_LT_S(stack);
                break;
            case I32x4_LT_U:
                I32X4_LT_U(stack);
                break;
            case I32x4_GT_S:
                I32X4_GT_S(stack);
                break;
            case I32x4_GT_U:
                I32X4_GT_U(stack);
                break;
            case I32x4_LE_S:
                I32X4_LE_S(stack);
                break;
            case I32x4_LE_U:
                I32X4_LE_U(stack);
                break;
            case I32x4_GE_S:
                I32X4_GE_S(stack);
                break;
            case I32x4_GE_U:
                I32X4_GE_U(stack);
                break;
            case F32x4_EQ:
                F32X4_EQ(stack);
                break;
            case F32x4_NE:
                F32X4_NE(stack);
                break;
            case F32x4_LT:
                F32X4_LT(stack);
                break;
            case F32x4_GT:
                F32X4_GT(stack);
                break;
            case F32x4_LE:
                F32X4_LE(stack);
                break;
            case F32x4_GE:
                F32X4_GE(stack);
                break;
            case F64x2_EQ:
                F64X2_EQ(stack);
                break;
            case F64x2_NE:
                F64X2_NE(stack);
                break;
            case F64x2_LT:
                F64X2_LT(stack);
                break;
            case F64x2_GT:
                F64X2_GT(stack);
                break;
            case F64x2_LE:
                F64X2_LE(stack);
                break;
            case F64x2_GE:
                F64X2_GE(stack);
                break;
            case V128_NOT:
                V128_NOT(stack);
                break;
            case V128_AND:
                V128_AND(stack);
                break;
            case V128_ANDNOT:
                V128_ANDNOT(stack);
                break;
            case V128_OR:
                V128_OR(stack);
                break;
            case V128_XOR:
                V128_XOR(stack);
                break;
            case V128_BITSELECT:
                V128_BITSELECT(stack);
                break;
            case V128_ANY_TRUE:
                V128_ANY_TRUE(stack);
                break;
            case V128_LOAD8_LANE:
                V128_LOAD8_LANE(stack, instance, instruction);
                break;
            case V128_LOAD16_LANE:
                V128_LOAD16_LANE(stack, instance, instruction);
                break;
            case V128_LOAD32_LANE:
                V128_LOAD32_LANE(stack, instance, instruction);
                break;
            case V128_LOAD64_LANE:
                V128_LOAD64_LANE(stack, instance, instruction);
                break;
            case V128_STORE8_LANE:
                V128_STORE8_LANE(stack, instance, instruction);
                break;
            case V128_STORE16_LANE:
                V128_STORE16_LANE(stack, instance, instruction);
                break;
            case V128_STORE32_LANE:
                V128_STORE32_LANE(stack, instance, instruction);
                break;
            case V128_STORE64_LANE:
                V128_STORE64_LANE(stack, instance, instruction);
                break;
            case V128_LOAD32_ZERO:
                V128_LOAD32_ZERO(stack, instance, instruction);
                break;
            case V128_LOAD64_ZERO:
                V128_LOAD64_ZERO(stack, instance, instruction);
                break;
            case F32x4_DEMOTE_LOW_F64x2_ZERO:
                F32X4_DEMOTE_LOW_F64X2_ZERO(stack);
                break;
            case F64x2_PROMOTE_LOW_F32x4:
                F64X2_PROMOTE_LOW_F32X4(stack);
                break;
            case I8x16_ABS:
                I8X16_ABS(stack);
                break;
            case I8x16_NEG:
                I8X16_NEG(stack);
                break;
            case I8x16_POPCNT:
                I8X16_POPCNT(stack);
                break;
            case I8x16_ALL_TRUE:
                I8X16_ALL_TRUE(stack);
                break;
            case I8x16_BITMASK:
                I8X16_BITMASK(stack);
                break;
            case I8x16_NARROW_I16x8_S:
                I8X16_NARROW_I16X8_S(stack);
                break;
            case I8x16_NARROW_I16x8_U:
                I8X16_NARROW_I16X8_U(stack);
                break;
            case F32x4_CEIL:
                F32X4_CEIL(stack);
                break;
            case F32x4_FLOOR:
                F32X4_FLOOR(stack);
                break;
            case F32x4_TRUNC:
                F32X4_TRUNC(stack);
                break;
            case F32x4_NEAREST:
                F32X4_NEAREST(stack);
                break;
            case I8x16_SHL:
                I8X16_SHL(stack);
                break;
            case I8x16_SHR_S:
                I8X16_SHR_S(stack);
                break;
            case I8x16_SHR_U:
                I8X16_SHR_U(stack);
                break;
            case I8x16_ADD:
                I8X16_ADD(stack);
                break;
            case I8x16_ADD_SAT_S:
                I8X16_ADD_SAT_S(stack);
                break;
            case I8x16_ADD_SAT_U:
                I8X16_ADD_SAT_U(stack);
                break;
            case I8x16_SUB:
                I8X16_SUB(stack);
                break;
            case I8x16_SUB_SAT_S:
                I8X16_SUB_SAT_S(stack);
                break;
            case I8x16_SUB_SAT_U:
                I8X16_SUB_SAT_U(stack);
                break;
            case F64x2_CEIL:
                F64X2_CEIL(stack);
                break;
            case F64x2_FLOOR:
                F64X2_FLOOR(stack);
                break;
            case I8x16_MIN_S:
                I8X16_MIN_S(stack);
                break;
            case I8x16_MIN_U:
                I8X16_MIN_U(stack);
                break;
            case I8x16_MAX_S:
                I8X16_MAX_S(stack);
                break;
            case I8x16_MAX_U:
                I8X16_MAX_U(stack);
                break;
            case F64x2_TRUNC:
                F64X2_TRUNC(stack);
                break;
            case I8x16_AVGR_U:
                I8X16_AVGR_U(stack);
                break;
            case I16x8_EXTADD_PAIRWISE_I8x16_S:
                I16X8_EXTADD_PAIRWISE_I8X16_S(stack);
                break;
            case I16x8_EXTADD_PAIRWISE_I8x16_U:
                I16X8_EXTADD_PAIRWISE_I8X16_U(stack);
                break;
            case I32x4_EXTADD_PAIRWISE_I16x8_S:
                I32X4_EXTADD_PAIRWISE_I16X8_S(stack);
                break;
            case I32x4_EXTADD_PAIRWISE_I16x8_U:
                I32X4_EXTADD_PAIRWISE_I16X8_U(stack);
                break;
            case I16x8_ABS:
                I16X8_ABS(stack);
                break;
            case I16x8_NEG:
                I16X8_NEG(stack);
                break;
            case I16x8_Q15MULR_SAT_S:
                I16X8_Q15MULR_SAT_S(stack);
                break;
            case I16x8_ALL_TRUE:
                I16X8_ALL_TRUE(stack);
                break;
            case I16x8_BITMASK:
                I16X8_BITMASK(stack);
                break;
            case I16x8_NARROW_I32x4_S:
                I16X8_NARROW_I32X4_S(stack);
                break;
            case I16x8_NARROW_I32x4_U:
                I16X8_NARROW_I32X4_U(stack);
                break;
            case I16x8_EXTEND_LOW_I8x16_S:
                I16X8_EXTEND_LOW_I8X16_S(stack);
                break;
            case I16x8_EXTEND_HIGH_I8x16_S:
                I16X8_EXTEND_HIGH_I8X16_S(stack);
                break;
            case I16x8_EXTEND_LOW_I8x16_U:
                I16X8_EXTEND_LOW_I8X16_U(stack);
                break;
            case I16x8_EXTEND_HIGH_I8x16_U:
                I16X8_EXTEND_HIGH_I8X16_U(stack);
                break;
            case I16x8_SHL:
                I16X8_SHL(stack);
                break;
            case I16x8_SHR_S:
                I16X8_SHR_S(stack);
                break;
            case I16x8_SHR_U:
                I16X8_SHR_U(stack);
                break;
            case I16x8_ADD:
                I16X8_ADD(stack);
                break;
            case I16x8_ADD_SAT_S:
                I16X8_ADD_SAT_S(stack);
                break;
            case I16x8_ADD_SAT_U:
                I16X8_ADD_SAT_U(stack);
                break;
            case I16x8_SUB:
                I16X8_SUB(stack);
                break;
            case I16x8_SUB_SAT_S:
                I16X8_SUB_SAT_S(stack);
                break;
            case I16x8_SUB_SAT_U:
                I16X8_SUB_SAT_U(stack);
                break;
            case F64x2_NEAREST:
                F64X2_NEAREST(stack);
                break;
            case I16x8_MUL:
                I16X8_MUL(stack);
                break;
            case I16x8_MIN_S:
                I16X8_MIN_S(stack);
                break;
            case I16x8_MIN_U:
                I16X8_MIN_U(stack);
                break;
            case I16x8_MAX_S:
                I16X8_MAX_S(stack);
                break;
            case I16x8_MAX_U:
                I16X8_MAX_U(stack);
                break;
            case I16x8_AVGR_U:
                I16X8_AVGR_U(stack);
                break;
            case I16x8_EXTMUL_LOW_I8x16_S:
                I16X8_EXTMUL_LOW_I8X16_S(stack);
                break;
            case I16x8_EXTMUL_HIGH_I8x16_S:
                I16X8_EXTMUL_HIGH_I8X16_S(stack);
                break;
            case I16x8_EXTMUL_LOW_I8x16_U:
                I16X8_EXTMUL_LOW_I8X16_U(stack);
                break;
            case I16x8_EXTMUL_HIGH_I8x16_U:
                I16X8_EXTMUL_HIGH_I8X16_U(stack);
                break;
            case I32x4_ABS:
                I32X4_ABS(stack);
                break;
            case I32x4_NEG:
                I32X4_NEG(stack);
                break;
            case I32x4_ALL_TRUE:
                I32X4_ALL_TRUE(stack);
                break;
            case I32x4_BITMASK:
                I32X4_BITMASK(stack);
                break;
            case I32x4_EXTEND_LOW_I16x8_S:
                I32X4_EXTEND_LOW_I16X8_S(stack);
                break;
            case I32x4_EXTEND_HIGH_I16x8_S:
                I32X4_EXTEND_HIGH_I16X8_S(stack);
                break;
            case I32x4_EXTEND_LOW_I16x8_U:
                I32X4_EXTEND_LOW_I16X8_U(stack);
                break;
            case I32x4_EXTEND_HIGH_I16x8_U:
                I32X4_EXTEND_HIGH_I16X8_U(stack);
                break;
            case I32x4_SHL:
                I32X4_SHL(stack);
                break;
            case I32x4_SHR_S:
                I32X4_SHR_S(stack);
                break;
            case I32x4_SHR_U:
                I32X4_SHR_U(stack);
                break;
            case I32x4_ADD:
                I32X4_ADD(stack);
                break;
            case I32x4_SUB:
                I32X4_SUB(stack);
                break;
            case I32x4_MUL:
                I32X4_MUL(stack);
                break;
            case I32x4_MIN_S:
                I32X4_MIN_S(stack);
                break;
            case I32x4_MIN_U:
                I32X4_MIN_U(stack);
                break;
            case I32x4_MAX_S:
                I32X4_MAX_S(stack);
                break;
            case I32x4_MAX_U:
                I32X4_MAX_U(stack);
                break;
            case I32x4_DOT_I16x8_S:
                I32X4_DOT_I16X8_S(stack);
                break;
            case I32x4_EXTMUL_LOW_I16x8_S:
                I32X4_EXTMUL_LOW_I16X8_S(stack);
                break;
            case I32x4_EXTMUL_HIGH_I16x8_S:
                I32X4_EXTMUL_HIGH_I16X8_S(stack);
                break;
            case I32x4_EXTMUL_LOW_I16x8_U:
                I32X4_EXTMUL_LOW_I16X8_U(stack);
                break;
            case I32x4_EXTMUL_HIGH_I16x8_U:
                I32X4_EXTMUL_HIGH_I16X8_U(stack);
                break;
            case I64x2_ABS:
                I64X2_ABS(stack);
                break;
            case I64x2_NEG:
                I64X2_NEG(stack);
                break;
            case I64x2_ALL_TRUE:
                I64X2_ALL_TRUE(stack);
                break;
            case I64x2_BITMASK:
                I64X2_BITMASK(stack);
                break;
            case I64x2_EXTEND_LOW_I32x4_S:
                I64X2_EXTEND_LOW_I32X4_S(stack);
                break;
            case I64x2_EXTEND_HIGH_I32x4_S:
                I64X2_EXTEND_HIGH_I32X4_S(stack);
                break;
            case I64x2_EXTEND_LOW_I32x4_U:
                I64X2_EXTEND_LOW_I32X4_U(stack);
                break;
            case I64x2_EXTEND_HIGH_I32x4_U:
                I64X2_EXTEND_HIGH_I32X4_U(stack);
                break;
            case I64x2_SHL:
                I64X2_SHL(stack);
                break;
            case I64x2_SHR_S:
                I64X2_SHR_S(stack);
                break;
            case I64x2_SHR_U:
                I64X2_SHR_U(stack);
                break;
            case I64x2_ADD:
                I64X2_ADD(stack);
                break;
            case I64x2_SUB:
                I64X2_SUB(stack);
                break;
            case I64x2_MUL:
                I64X2_MUL(stack);
                break;
            case I64x2_EQ:
                I64X2_EQ(stack);
                break;
            case I64x2_NE:
                I64X2_NE(stack);
                break;
            case I64x2_LT_S:
                I64X2_LT_S(stack);
                break;
            case I64x2_GT_S:
                I64X2_GT_S(stack);
                break;
            case I64x2_LE_S:
                I64X2_LE_S(stack);
                break;
            case I64x2_GE_S:
                I64X2_GE_S(stack);
                break;
            case I64x2_EXTMUL_LOW_I32x4_S:
                I64X2_EXTMUL_LOW_I32X4_S(stack);
                break;
            case I64x2_EXTMUL_HIGH_I32x4_S:
                I64X2_EXTMUL_HIGH_I32X4_S(stack);
                break;
            case I64x2_EXTMUL_LOW_I32x4_U:
                I64X2_EXTMUL_LOW_I32X4_U(stack);
                break;
            case I64x2_EXTMUL_HIGH_I32x4_U:
                I64X2_EXTMUL_HIGH_I32X4_U(stack);
                break;
            case F32x4_ABS:
                F32X4_ABS(stack);
                break;
            case F32x4_NEG:
                F32X4_NEG(stack);
                break;
            case F32x4_SQRT:
                F32X4_SQRT(stack);
                break;
            case F32x4_ADD:
                F32X4_ADD(stack);
                break;
            case F32x4_SUB:
                F32X4_SUB(stack);
                break;
            case F32x4_MUL:
                F32X4_MUL(stack);
                break;
            case F32x4_DIV:
                F32X4_DIV(stack);
                break;
            case F32x4_MIN:
                F32X4_MIN(stack);
                break;
            case F32x4_MAX:
                F32X4_MAX(stack);
                break;
            case F32x4_PMIN:
                F32X4_PMIN(stack);
                break;
            case F32x4_PMAX:
                F32X4_PMAX(stack);
                break;
            case F64x2_ABS:
                F64X2_ABS(stack);
                break;
            case F64x2_NEG:
                F64X2_NEG(stack);
                break;
            case F64x2_SQRT:
                F64X2_SQRT(stack);
                break;
            case F64x2_ADD:
                F64X2_ADD(stack);
                break;
            case F64x2_SUB:
                F64X2_SUB(stack);
                break;
            case F64x2_MUL:
                F64X2_MUL(stack);
                break;
            case F64x2_DIV:
                F64X2_DIV(stack);
                break;
            case F64x2_MIN:
                F64X2_MIN(stack);
                break;
            case F64x2_MAX:
                F64X2_MAX(stack);
                break;
            case F64x2_PMIN:
                F64X2_PMIN(stack);
                break;
            case F64x2_PMAX:
                F64X2_PMAX(stack);
                break;
            case I32x4_TRUNC_SAT_F32X4_S:
                I32X4_TRUNC_SAT_F32X4_S(stack);
                break;
            case I32x4_TRUNC_SAT_F32X4_U:
                I32X4_TRUNC_SAT_F32X4_U(stack);
                break;
            case F32x4_CONVERT_I32x4_S:
                F32X4_CONVERT_I32X4_S(stack);
                break;
            case F32x4_CONVERT_I32x4_U:
                F32X4_CONVERT_I32X4_U(stack);
                break;
            case I32x4_TRUNC_SAT_F64x2_S_ZERO:
                I32X4_TRUNC_SAT_F64X2_S_ZERO(stack);
                break;
            case I32x4_TRUNC_SAT_F64x2_U_ZERO:
                I32X4_TRUNC_SAT_F64X2_U_ZERO(stack);
                break;
            case F64x2_CONVERT_LOW_I32x4_S:
                F64X2_CONVERT_LOW_I32X4_S(stack);
                break;
            case F64x2_CONVERT_LOW_I32x4_U:
                F64X2_CONVERT_LOW_I32X4_U(stack);
                break;
            default:
                return false;
        }
        return true;
    }

    private static void V128_LOAD(MStack stack, Instance instance, Instruction instruction) {
        load(stack, instance, instruction);
    }

    private static void V128_LOAD8X8_S(MStack stack, Instance instance, Instruction instruction) {
        loadExtend(stack, instance, instruction, 8, true);
    }

    private static void V128_LOAD8X8_U(MStack stack, Instance instance, Instruction instruction) {
        loadExtend(stack, instance, instruction, 8, false);
    }

    private static void V128_LOAD16X4_S(MStack stack, Instance instance, Instruction instruction) {
        loadExtend(stack, instance, instruction, 16, true);
    }

    private static void V128_LOAD16X4_U(MStack stack, Instance instance, Instruction instruction) {
        loadExtend(stack, instance, instruction, 16, false);
    }

    private static void V128_LOAD32X2_S(MStack stack, Instance instance, Instruction instruction) {
        loadExtend(stack, instance, instruction, 32, true);
    }

    private static void V128_LOAD32X2_U(MStack stack, Instance instance, Instruction instruction) {
        loadExtend(stack, instance, instruction, 32, false);
    }

    private static void V128_LOAD8_SPLAT(MStack stack, Instance instance, Instruction instruction) {
        loadSplat(stack, instance, instruction, 8);
    }

    private static void V128_LOAD16_SPLAT(
            MStack stack, Instance instance, Instruction instruction) {
        loadSplat(stack, instance, instruction, 16);
    }

    private static void V128_LOAD32_SPLAT(
            MStack stack, Instance instance, Instruction instruction) {
        loadSplat(stack, instance, instruction, 32);
    }

    private static void V128_LOAD64_SPLAT(
            MStack stack, Instance instance, Instruction instruction) {
        loadSplat(stack, instance, instruction, 64);
    }

    private static void V128_STORE(MStack stack, Instance instance, Instruction instruction) {
        store(stack, instance, instruction);
    }

    private static void V128_CONST(MStack stack, Instruction instruction) {
        stack.push(instruction.operand(0));
        stack.push(instruction.operand(1));
    }

    private static void I8X16_SHUFFLE(MStack stack, Instruction instruction) {
        shuffle(stack, instruction);
    }

    private static void I8X16_SWIZZLE(MStack stack) {
        swizzle(stack);
    }

    private static void I8X16_SPLAT(MStack stack) {
        splat(stack, 8);
    }

    private static void I16X8_SPLAT(MStack stack) {
        splat(stack, 16);
    }

    private static void I32X4_SPLAT(MStack stack) {
        splat(stack, 32);
    }

    private static void I64X2_SPLAT(MStack stack) {
        splat(stack, 64);
    }

    private static void F32X4_SPLAT(MStack stack) {
        splat(stack, 32);
    }

    private static void F64X2_SPLAT(MStack stack) {
        splat(stack, 64);
    }

    private static void I8X16_EXTRACT_LANE_S(MStack stack, Instruction instruction) {
        extract(stack, instruction, 8, true);
    }

    private static void I8X16_EXTRACT_LANE_U(MStack stack, Instruction instruction) {
        extract(stack, instruction, 8, false);
    }

    private static void I8X16_REPLACE_LANE(MStack stack, Instruction instruction) {
        replace(stack, instruction, 8);
    }

    private static void I16X8_EXTRACT_LANE_S(MStack stack, Instruction instruction) {
        extract(stack, instruction, 16, true);
    }

    private static void I16X8_EXTRACT_LANE_U(MStack stack, Instruction instruction) {
        extract(stack, instruction, 16, false);
    }

    private static void I16X8_REPLACE_LANE(MStack stack, Instruction instruction) {
        replace(stack, instruction, 16);
    }

    private static void I32X4_EXTRACT_LANE(MStack stack, Instruction instruction) {
        extract(stack, instruction, 32, true);
    }

    private static void I32X4_REPLACE_LANE(MStack stack, Instruction instruction) {
        replace(stack, instruction, 32);
    }

    private static void I64X2_EXTRACT_LANE(MStack stack, Instruction instruction) {
        extract(stack, instruction, 64, false);
    }

    private static void I64X2_REPLACE_LANE(MStack stack, Instruction instruction) {
        replace(stack, instruction, 64);
    }

    private static void F32X4_EXTRACT_LANE(MStack stack, Instruction instruction) {
        extract(stack, instruction, 32, true);
    }

    private static void F32X4_REPLACE_LANE(MStack stack, Instruction instruction) {
        replace(stack, instruction, 32);
    }

    private static void F64X2_EXTRACT_LANE(MStack stack, Instruction instruction) {
        extract(stack, instruction, 64, false);
    }

    private static void F64X2_REPLACE_LANE(MStack stack, Instruction instruction) {
        replace(stack, instruction, 64);
    }

    private static void I8X16_EQ(MStack stack) {
        binaryInt(stack, 8, EQ);
    }

    private static void I8X16_NE(MStack stack) {
        binaryInt(stack, 8, NE);
    }

    private static void I8X16_LT_S(MStack stack) {
        binaryInt(stack, 8, LT_S);
    }

    private static void I8X16_LT_U(MStack stack) {
        binaryInt(stack, 8, LT_U);
    }

    private static void I8X16_GT_S(MStack stack) {
        binaryInt(stack, 8, GT_S);
    }

    private static void I8X16_GT_U(MStack stack) {
        binaryInt(stack, 8, GT_U);
    }

    private static void I8X16_LE_S(MStack stack) {
        binaryInt(stack, 8, LE_S);
    }

    private static void I8X16_LE_U(MStack stack) {
        binaryInt(stack, 8, LE_U);
    }

    private static void I8X16_GE_S(MStack stack) {
        binaryInt(stack, 8, GE_S);
    }

    private static void I8X16_GE_U(MStack stack) {
        binaryInt(stack, 8, GE_U);
    }

    private static void I16X8_EQ(MStack stack) {
        binaryInt(stack, 16, EQ);
    }

    private static void I16X8_NE(MStack stack) {
        binaryInt(stack, 16, NE);
    }

    private static void I16X8_LT_S(MStack stack) {
        binaryInt(stack, 16, LT_S);
    }

    private static void I16X8_LT_U(MStack stack) {
        binaryInt(stack, 16, LT_U);
    }

    private static void I16X8_GT_S(MStack stack) {
        binaryInt(stack, 16, GT_S);
    }

    private static void I16X8_GT_U(MStack stack) {
        binaryInt(stack, 16, GT_U);
    }

    private static void I16X8_LE_S(MStack stack) {
        binaryInt(stack, 16, LE_S);
    }

    private static void I16X8_LE_U(MStack stack) {
        binaryInt(stack, 16, LE_U);
    }

    private static void I16X8_GE_S(MStack stack) {
        binaryInt(stack, 16, GE_S);
    }

    private static void I16X8_GE_U(MStack stack) {
        binaryInt(stack, 16, GE_U);
    }

    private static void I32X4_EQ(MStack stack) {
        binaryInt(stack, 32, EQ);
    }

    private static void I32X4_NE(MStack stack) {
        binaryInt(stack, 32, NE);
    }

    private static void I32X4_LT_S(MStack stack) {
        binaryInt(stack, 32, LT_S);
    }

    private static void I32X4_LT_U(MStack stack) {
        binaryInt(stack, 32, LT_U);
    }

    private static void I32X4_GT_S(MStack stack) {
        binaryInt(stack, 32, GT_S);
    }

    private static void I32X4_GT_U(MStack stack) {
        binaryInt(stack, 32, GT_U);
    }

    private static void I32X4_LE_S(MStack stack) {
        binaryInt(stack, 32, LE_S);
    }

    private static void I32X4_LE_U(MStack stack) {
        binaryInt(stack, 32, LE_U);
    }

    private static void I32X4_GE_S(MStack stack) {
        binaryInt(stack, 32, GE_S);
    }

    private static void I32X4_GE_U(MStack stack) {
        binaryInt(stack, 32, GE_U);
    }

    private static void F32X4_EQ(MStack stack) {
        binaryFloat(stack, 32, EQ);
    }

    private static void F32X4_NE(MStack stack) {
        binaryFloat(stack, 32, NE);
    }

    private static void F32X4_LT(MStack stack) {
        binaryFloat(stack, 32, LT);
    }

    private static void F32X4_GT(MStack stack) {
        binaryFloat(stack, 32, GT);
    }

    private static void F32X4_LE(MStack stack) {
        binaryFloat(stack, 32, LE);
    }

    private static void F32X4_GE(MStack stack) {
        binaryFloat(stack, 32, GE);
    }

    private static void F64X2_EQ(MStack stack) {
        binaryFloat(stack, 64, EQ);
    }

    private static void F64X2_NE(MStack stack) {
        binaryFloat(stack, 64, NE);
    }

    private static void F64X2_LT(MStack stack) {
        binaryFloat(stack, 64, LT);
    }

    private static void F64X2_GT(MStack stack) {
        binaryFloat(stack, 64, GT);
    }

    private static void F64X2_LE(MStack stack) {
        binaryFloat(stack, 64, LE);
    }

    private static void F64X2_GE(MStack stack) {
        binaryFloat(stack, 64, GE);
    }

    private static void V128_NOT(MStack stack) {
        int offset = stack.size() - 2;
        stack.array()[offset] = ~stack.array()[offset];
        stack.array()[offset + 1] = ~stack.array()[offset + 1];
    }

    private static void V128_AND(MStack stack) {
        bitwise(stack, AND);
    }

    private static void V128_ANDNOT(MStack stack) {
        bitwise(stack, ANDNOT);
    }

    private static void V128_OR(MStack stack) {
        bitwise(stack, OR);
    }

    private static void V128_XOR(MStack stack) {
        bitwise(stack, XOR);
    }

    private static void V128_BITSELECT(MStack stack) {
        bitselect(stack);
    }

    private static void V128_ANY_TRUE(MStack stack) {
        anyTrue(stack);
    }

    private static void V128_LOAD8_LANE(MStack stack, Instance instance, Instruction instruction) {
        loadLane(stack, instance, instruction, 8);
    }

    private static void V128_LOAD16_LANE(MStack stack, Instance instance, Instruction instruction) {
        loadLane(stack, instance, instruction, 16);
    }

    private static void V128_LOAD32_LANE(MStack stack, Instance instance, Instruction instruction) {
        loadLane(stack, instance, instruction, 32);
    }

    private static void V128_LOAD64_LANE(MStack stack, Instance instance, Instruction instruction) {
        loadLane(stack, instance, instruction, 64);
    }

    private static void V128_STORE8_LANE(MStack stack, Instance instance, Instruction instruction) {
        storeLane(stack, instance, instruction, 8);
    }

    private static void V128_STORE16_LANE(
            MStack stack, Instance instance, Instruction instruction) {
        storeLane(stack, instance, instruction, 16);
    }

    private static void V128_STORE32_LANE(
            MStack stack, Instance instance, Instruction instruction) {
        storeLane(stack, instance, instruction, 32);
    }

    private static void V128_STORE64_LANE(
            MStack stack, Instance instance, Instruction instruction) {
        storeLane(stack, instance, instruction, 64);
    }

    private static void V128_LOAD32_ZERO(MStack stack, Instance instance, Instruction instruction) {
        loadZero(stack, instance, instruction, 32);
    }

    private static void V128_LOAD64_ZERO(MStack stack, Instance instance, Instruction instruction) {
        loadZero(stack, instance, instruction, 64);
    }

    private static void F32X4_DEMOTE_LOW_F64X2_ZERO(MStack stack) {
        demote(stack);
    }

    private static void F64X2_PROMOTE_LOW_F32X4(MStack stack) {
        promote(stack);
    }

    private static void I8X16_ABS(MStack stack) {
        unaryInt(stack, 8, ABS);
    }

    private static void I8X16_NEG(MStack stack) {
        unaryInt(stack, 8, NEG);
    }

    private static void I8X16_POPCNT(MStack stack) {
        unaryInt(stack, 8, POPCNT);
    }

    private static void I8X16_ALL_TRUE(MStack stack) {
        allTrue(stack, 8);
    }

    private static void I8X16_BITMASK(MStack stack) {
        bitmask(stack, 8);
    }

    private static void I8X16_NARROW_I16X8_S(MStack stack) {
        narrow(stack, 16, true);
    }

    private static void I8X16_NARROW_I16X8_U(MStack stack) {
        narrow(stack, 16, false);
    }

    private static void F32X4_CEIL(MStack stack) {
        unaryFloat(stack, 32, CEIL);
    }

    private static void F32X4_FLOOR(MStack stack) {
        unaryFloat(stack, 32, FLOOR);
    }

    private static void F32X4_TRUNC(MStack stack) {
        unaryFloat(stack, 32, TRUNC);
    }

    private static void F32X4_NEAREST(MStack stack) {
        unaryFloat(stack, 32, NEAREST);
    }

    private static void I8X16_SHL(MStack stack) {
        shift(stack, 8, SHL);
    }

    private static void I8X16_SHR_S(MStack stack) {
        shift(stack, 8, SHR_S);
    }

    private static void I8X16_SHR_U(MStack stack) {
        shift(stack, 8, SHR_U);
    }

    private static void I8X16_ADD(MStack stack) {
        binaryInt(stack, 8, ADD);
    }

    private static void I8X16_ADD_SAT_S(MStack stack) {
        binaryInt(stack, 8, ADD_SAT_S);
    }

    private static void I8X16_ADD_SAT_U(MStack stack) {
        binaryInt(stack, 8, ADD_SAT_U);
    }

    private static void I8X16_SUB(MStack stack) {
        binaryInt(stack, 8, SUB);
    }

    private static void I8X16_SUB_SAT_S(MStack stack) {
        binaryInt(stack, 8, SUB_SAT_S);
    }

    private static void I8X16_SUB_SAT_U(MStack stack) {
        binaryInt(stack, 8, SUB_SAT_U);
    }

    private static void F64X2_CEIL(MStack stack) {
        unaryFloat(stack, 64, CEIL);
    }

    private static void F64X2_FLOOR(MStack stack) {
        unaryFloat(stack, 64, FLOOR);
    }

    private static void I8X16_MIN_S(MStack stack) {
        binaryInt(stack, 8, MIN_S);
    }

    private static void I8X16_MIN_U(MStack stack) {
        binaryInt(stack, 8, MIN_U);
    }

    private static void I8X16_MAX_S(MStack stack) {
        binaryInt(stack, 8, MAX_S);
    }

    private static void I8X16_MAX_U(MStack stack) {
        binaryInt(stack, 8, MAX_U);
    }

    private static void F64X2_TRUNC(MStack stack) {
        unaryFloat(stack, 64, TRUNC);
    }

    private static void I8X16_AVGR_U(MStack stack) {
        binaryInt(stack, 8, AVGR_U);
    }

    private static void I16X8_EXTADD_PAIRWISE_I8X16_S(MStack stack) {
        pairwise(stack, 8, true);
    }

    private static void I16X8_EXTADD_PAIRWISE_I8X16_U(MStack stack) {
        pairwise(stack, 8, false);
    }

    private static void I32X4_EXTADD_PAIRWISE_I16X8_S(MStack stack) {
        pairwise(stack, 16, true);
    }

    private static void I32X4_EXTADD_PAIRWISE_I16X8_U(MStack stack) {
        pairwise(stack, 16, false);
    }

    private static void I16X8_ABS(MStack stack) {
        unaryInt(stack, 16, ABS);
    }

    private static void I16X8_NEG(MStack stack) {
        unaryInt(stack, 16, NEG);
    }

    private static void I16X8_Q15MULR_SAT_S(MStack stack) {
        q15(stack);
    }

    private static void I16X8_ALL_TRUE(MStack stack) {
        allTrue(stack, 16);
    }

    private static void I16X8_BITMASK(MStack stack) {
        bitmask(stack, 16);
    }

    private static void I16X8_NARROW_I32X4_S(MStack stack) {
        narrow(stack, 32, true);
    }

    private static void I16X8_NARROW_I32X4_U(MStack stack) {
        narrow(stack, 32, false);
    }

    private static void I16X8_EXTEND_LOW_I8X16_S(MStack stack) {
        extend(stack, 8, true, false);
    }

    private static void I16X8_EXTEND_HIGH_I8X16_S(MStack stack) {
        extend(stack, 8, true, true);
    }

    private static void I16X8_EXTEND_LOW_I8X16_U(MStack stack) {
        extend(stack, 8, false, false);
    }

    private static void I16X8_EXTEND_HIGH_I8X16_U(MStack stack) {
        extend(stack, 8, false, true);
    }

    private static void I16X8_SHL(MStack stack) {
        shift(stack, 16, SHL);
    }

    private static void I16X8_SHR_S(MStack stack) {
        shift(stack, 16, SHR_S);
    }

    private static void I16X8_SHR_U(MStack stack) {
        shift(stack, 16, SHR_U);
    }

    private static void I16X8_ADD(MStack stack) {
        binaryInt(stack, 16, ADD);
    }

    private static void I16X8_ADD_SAT_S(MStack stack) {
        binaryInt(stack, 16, ADD_SAT_S);
    }

    private static void I16X8_ADD_SAT_U(MStack stack) {
        binaryInt(stack, 16, ADD_SAT_U);
    }

    private static void I16X8_SUB(MStack stack) {
        binaryInt(stack, 16, SUB);
    }

    private static void I16X8_SUB_SAT_S(MStack stack) {
        binaryInt(stack, 16, SUB_SAT_S);
    }

    private static void I16X8_SUB_SAT_U(MStack stack) {
        binaryInt(stack, 16, SUB_SAT_U);
    }

    private static void F64X2_NEAREST(MStack stack) {
        unaryFloat(stack, 64, NEAREST);
    }

    private static void I16X8_MUL(MStack stack) {
        binaryInt(stack, 16, MUL);
    }

    private static void I16X8_MIN_S(MStack stack) {
        binaryInt(stack, 16, MIN_S);
    }

    private static void I16X8_MIN_U(MStack stack) {
        binaryInt(stack, 16, MIN_U);
    }

    private static void I16X8_MAX_S(MStack stack) {
        binaryInt(stack, 16, MAX_S);
    }

    private static void I16X8_MAX_U(MStack stack) {
        binaryInt(stack, 16, MAX_U);
    }

    private static void I16X8_AVGR_U(MStack stack) {
        binaryInt(stack, 16, AVGR_U);
    }

    private static void I16X8_EXTMUL_LOW_I8X16_S(MStack stack) {
        extmul(stack, 8, true, false);
    }

    private static void I16X8_EXTMUL_HIGH_I8X16_S(MStack stack) {
        extmul(stack, 8, true, true);
    }

    private static void I16X8_EXTMUL_LOW_I8X16_U(MStack stack) {
        extmul(stack, 8, false, false);
    }

    private static void I16X8_EXTMUL_HIGH_I8X16_U(MStack stack) {
        extmul(stack, 8, false, true);
    }

    private static void I32X4_ABS(MStack stack) {
        unaryInt(stack, 32, ABS);
    }

    private static void I32X4_NEG(MStack stack) {
        unaryInt(stack, 32, NEG);
    }

    private static void I32X4_ALL_TRUE(MStack stack) {
        allTrue(stack, 32);
    }

    private static void I32X4_BITMASK(MStack stack) {
        bitmask(stack, 32);
    }

    private static void I32X4_EXTEND_LOW_I16X8_S(MStack stack) {
        extend(stack, 16, true, false);
    }

    private static void I32X4_EXTEND_HIGH_I16X8_S(MStack stack) {
        extend(stack, 16, true, true);
    }

    private static void I32X4_EXTEND_LOW_I16X8_U(MStack stack) {
        extend(stack, 16, false, false);
    }

    private static void I32X4_EXTEND_HIGH_I16X8_U(MStack stack) {
        extend(stack, 16, false, true);
    }

    private static void I32X4_SHL(MStack stack) {
        shift(stack, 32, SHL);
    }

    private static void I32X4_SHR_S(MStack stack) {
        shift(stack, 32, SHR_S);
    }

    private static void I32X4_SHR_U(MStack stack) {
        shift(stack, 32, SHR_U);
    }

    private static void I32X4_ADD(MStack stack) {
        binaryInt(stack, 32, ADD);
    }

    private static void I32X4_SUB(MStack stack) {
        binaryInt(stack, 32, SUB);
    }

    private static void I32X4_MUL(MStack stack) {
        binaryInt(stack, 32, MUL);
    }

    private static void I32X4_MIN_S(MStack stack) {
        binaryInt(stack, 32, MIN_S);
    }

    private static void I32X4_MIN_U(MStack stack) {
        binaryInt(stack, 32, MIN_U);
    }

    private static void I32X4_MAX_S(MStack stack) {
        binaryInt(stack, 32, MAX_S);
    }

    private static void I32X4_MAX_U(MStack stack) {
        binaryInt(stack, 32, MAX_U);
    }

    private static void I32X4_DOT_I16X8_S(MStack stack) {
        dot(stack);
    }

    private static void I32X4_EXTMUL_LOW_I16X8_S(MStack stack) {
        extmul(stack, 16, true, false);
    }

    private static void I32X4_EXTMUL_HIGH_I16X8_S(MStack stack) {
        extmul(stack, 16, true, true);
    }

    private static void I32X4_EXTMUL_LOW_I16X8_U(MStack stack) {
        extmul(stack, 16, false, false);
    }

    private static void I32X4_EXTMUL_HIGH_I16X8_U(MStack stack) {
        extmul(stack, 16, false, true);
    }

    private static void I64X2_ABS(MStack stack) {
        unaryInt(stack, 64, ABS);
    }

    private static void I64X2_NEG(MStack stack) {
        unaryInt(stack, 64, NEG);
    }

    private static void I64X2_ALL_TRUE(MStack stack) {
        allTrue(stack, 64);
    }

    private static void I64X2_BITMASK(MStack stack) {
        bitmask(stack, 64);
    }

    private static void I64X2_EXTEND_LOW_I32X4_S(MStack stack) {
        extend(stack, 32, true, false);
    }

    private static void I64X2_EXTEND_HIGH_I32X4_S(MStack stack) {
        extend(stack, 32, true, true);
    }

    private static void I64X2_EXTEND_LOW_I32X4_U(MStack stack) {
        extend(stack, 32, false, false);
    }

    private static void I64X2_EXTEND_HIGH_I32X4_U(MStack stack) {
        extend(stack, 32, false, true);
    }

    private static void I64X2_SHL(MStack stack) {
        shift(stack, 64, SHL);
    }

    private static void I64X2_SHR_S(MStack stack) {
        shift(stack, 64, SHR_S);
    }

    private static void I64X2_SHR_U(MStack stack) {
        shift(stack, 64, SHR_U);
    }

    private static void I64X2_ADD(MStack stack) {
        binaryInt(stack, 64, ADD);
    }

    private static void I64X2_SUB(MStack stack) {
        binaryInt(stack, 64, SUB);
    }

    private static void I64X2_MUL(MStack stack) {
        binaryInt(stack, 64, MUL);
    }

    private static void I64X2_EQ(MStack stack) {
        binaryInt(stack, 64, EQ);
    }

    private static void I64X2_NE(MStack stack) {
        binaryInt(stack, 64, NE);
    }

    private static void I64X2_LT_S(MStack stack) {
        binaryInt(stack, 64, LT_S);
    }

    private static void I64X2_GT_S(MStack stack) {
        binaryInt(stack, 64, GT_S);
    }

    private static void I64X2_LE_S(MStack stack) {
        binaryInt(stack, 64, LE_S);
    }

    private static void I64X2_GE_S(MStack stack) {
        binaryInt(stack, 64, GE_S);
    }

    private static void I64X2_EXTMUL_LOW_I32X4_S(MStack stack) {
        extmul(stack, 32, true, false);
    }

    private static void I64X2_EXTMUL_HIGH_I32X4_S(MStack stack) {
        extmul(stack, 32, true, true);
    }

    private static void I64X2_EXTMUL_LOW_I32X4_U(MStack stack) {
        extmul(stack, 32, false, false);
    }

    private static void I64X2_EXTMUL_HIGH_I32X4_U(MStack stack) {
        extmul(stack, 32, false, true);
    }

    private static void F32X4_ABS(MStack stack) {
        unaryFloat(stack, 32, ABS);
    }

    private static void F32X4_NEG(MStack stack) {
        unaryFloat(stack, 32, NEG);
    }

    private static void F32X4_SQRT(MStack stack) {
        unaryFloat(stack, 32, SQRT);
    }

    private static void F32X4_ADD(MStack stack) {
        binaryFloat(stack, 32, ADD);
    }

    private static void F32X4_SUB(MStack stack) {
        binaryFloat(stack, 32, SUB);
    }

    private static void F32X4_MUL(MStack stack) {
        binaryFloat(stack, 32, MUL);
    }

    private static void F32X4_DIV(MStack stack) {
        binaryFloat(stack, 32, DIV);
    }

    private static void F32X4_MIN(MStack stack) {
        binaryFloat(stack, 32, MIN);
    }

    private static void F32X4_MAX(MStack stack) {
        binaryFloat(stack, 32, MAX);
    }

    private static void F32X4_PMIN(MStack stack) {
        binaryFloat(stack, 32, PMIN);
    }

    private static void F32X4_PMAX(MStack stack) {
        binaryFloat(stack, 32, PMAX);
    }

    private static void F64X2_ABS(MStack stack) {
        unaryFloat(stack, 64, ABS);
    }

    private static void F64X2_NEG(MStack stack) {
        unaryFloat(stack, 64, NEG);
    }

    private static void F64X2_SQRT(MStack stack) {
        unaryFloat(stack, 64, SQRT);
    }

    private static void F64X2_ADD(MStack stack) {
        binaryFloat(stack, 64, ADD);
    }

    private static void F64X2_SUB(MStack stack) {
        binaryFloat(stack, 64, SUB);
    }

    private static void F64X2_MUL(MStack stack) {
        binaryFloat(stack, 64, MUL);
    }

    private static void F64X2_DIV(MStack stack) {
        binaryFloat(stack, 64, DIV);
    }

    private static void F64X2_MIN(MStack stack) {
        binaryFloat(stack, 64, MIN);
    }

    private static void F64X2_MAX(MStack stack) {
        binaryFloat(stack, 64, MAX);
    }

    private static void F64X2_PMIN(MStack stack) {
        binaryFloat(stack, 64, PMIN);
    }

    private static void F64X2_PMAX(MStack stack) {
        binaryFloat(stack, 64, PMAX);
    }

    private static void I32X4_TRUNC_SAT_F32X4_S(MStack stack) {
        truncSatF32(stack, true);
    }

    private static void I32X4_TRUNC_SAT_F32X4_U(MStack stack) {
        truncSatF32(stack, false);
    }

    private static void F32X4_CONVERT_I32X4_S(MStack stack) {
        convertI32ToF32(stack, true);
    }

    private static void F32X4_CONVERT_I32X4_U(MStack stack) {
        convertI32ToF32(stack, false);
    }

    private static void I32X4_TRUNC_SAT_F64X2_S_ZERO(MStack stack) {
        truncSatF64Zero(stack, true);
    }

    private static void I32X4_TRUNC_SAT_F64X2_U_ZERO(MStack stack) {
        truncSatF64Zero(stack, false);
    }

    private static void F64X2_CONVERT_LOW_I32X4_S(MStack stack) {
        convertLowI32ToF64(stack, true);
    }

    private static void F64X2_CONVERT_LOW_I32X4_U(MStack stack) {
        convertLowI32ToF64(stack, false);
    }

    private static void load(MStack stack, Instance instance, Instruction ins) {
        int ptr = readMemPtr(stack, ins);
        var memory = instance.memory((int) ins.operand(2));
        stack.push(memory.readLong(ptr));
        stack.push(memory.readLong(ptr + 8));
    }

    private static void loadExtend(
            MStack stack, Instance instance, Instruction ins, int width, boolean signed) {
        int ptr = readMemPtr(stack, ins);
        var memory = instance.memory((int) ins.operand(2));
        long low = 0;
        long high = 0;
        int count = 64 / width;
        for (int i = 0; i < count; i++) {
            long value;
            int at = ptr + i * (width / 8);
            if (width == 8) {
                value = signed ? memory.read(at) : memory.readU8(at);
            } else if (width == 16) {
                value = signed ? memory.readShort(at) : memory.readU16(at);
            } else {
                value = signed ? memory.readInt(at) : memory.readU32(at);
            }
            if (i < 64 / (width * 2)) {
                low = put(low, i, width * 2, value);
            } else {
                high = put(high, i - 64 / (width * 2), width * 2, value);
            }
        }
        stack.push(low);
        stack.push(high);
    }

    private static void loadSplat(MStack stack, Instance instance, Instruction ins, int width) {
        int ptr = readMemPtr(stack, ins);
        var memory = instance.memory((int) ins.operand(2));
        long value =
                width == 8
                        ? memory.read(ptr)
                        : width == 16
                                ? memory.readShort(ptr)
                                : width == 32 ? memory.readInt(ptr) : memory.readLong(ptr);
        stack.push(repeat(value, width));
        stack.push(repeat(value, width));
    }

    private static void loadZero(MStack stack, Instance instance, Instruction ins, int width) {
        int ptr = readMemPtr(stack, ins);
        var memory = instance.memory((int) ins.operand(2));
        stack.push(width == 32 ? memory.readU32(ptr) : memory.readLong(ptr));
        stack.push(0);
    }

    private static void store(MStack stack, Instance instance, Instruction ins) {
        long high = stack.pop();
        long low = stack.pop();
        int ptr = readMemPtr(stack, ins);
        var memory = instance.memory((int) ins.operand(2));
        memory.writeLong(ptr, low);
        memory.writeLong(ptr + 8, high);
    }

    private static void loadLane(MStack stack, Instance instance, Instruction ins, int width) {
        long high = stack.pop();
        long low = stack.pop();
        int ptr = readMemPtr(stack, ins);
        int lane = (int) ins.operand(3);
        var memory = instance.memory((int) ins.operand(2));
        long value =
                width == 8
                        ? memory.read(ptr)
                        : width == 16
                                ? memory.readShort(ptr)
                                : width == 32 ? memory.readInt(ptr) : memory.readLong(ptr);
        if (lane < 64 / width) {
            low = put(low, lane, width, value);
        } else {
            high = put(high, lane - 64 / width, width, value);
        }
        stack.push(low);
        stack.push(high);
    }

    private static void storeLane(MStack stack, Instance instance, Instruction ins, int width) {
        long high = stack.pop();
        long low = stack.pop();
        int ptr = readMemPtr(stack, ins);
        int lane = (int) ins.operand(3);
        long value = get(lane < 64 / width ? low : high, lane % (64 / width), width);
        var memory = instance.memory((int) ins.operand(2));
        if (width == 8) {
            memory.writeByte(ptr, (byte) value);
        } else if (width == 16) {
            memory.writeShort(ptr, (short) value);
        } else if (width == 32) {
            memory.writeI32(ptr, (int) value);
        } else {
            memory.writeLong(ptr, value);
        }
    }

    static int readMemPtr(MStack stack, Instruction ins) {
        int address = (int) stack.pop();
        if (ins.operand(1) < 0 || ins.operand(1) >= Integer.MAX_VALUE || address < 0) {
            throw new WasmRuntimeException("out of bounds memory access");
        }
        return (int) (ins.operand(1) + address);
    }

    private static void splat(MStack stack, int width) {
        long value = stack.pop();
        long repeated = repeat(value, width);
        stack.push(repeated);
        stack.push(repeated);
    }

    private static long repeat(long value, int width) {
        if (width == 8) {
            return (value & 0xffL) * 0x0101010101010101L;
        }
        if (width == 16) {
            return (value & 0xffffL) * 0x0001000100010001L;
        }
        if (width == 32) {
            return (value & 0xffffffffL) | ((value & 0xffffffffL) << 32);
        }
        return value;
    }

    private static void shuffle(MStack stack, Instruction ins) {
        long rightHigh = stack.pop();
        long rightLow = stack.pop();
        long leftHigh = stack.pop();
        long leftLow = stack.pop();
        long low = 0;
        long high = 0;
        for (int i = 0; i < 16; i++) {
            int lane =
                    (int) (i < 8 ? ins.operand(0) >>> (i * 8) : ins.operand(1) >>> ((i - 8) * 8))
                            & 0xff;
            long value =
                    lane < 8
                            ? get(leftLow, lane, 8)
                            : lane < 16
                                    ? get(leftHigh, lane - 8, 8)
                                    : lane < 24
                                            ? get(rightLow, lane - 16, 8)
                                            : get(rightHigh, lane - 24, 8);
            if (i < 8) {
                low = put(low, i, 8, value);
            } else {
                high = put(high, i - 8, 8, value);
            }
        }
        stack.push(low);
        stack.push(high);
    }

    private static void swizzle(MStack stack) {
        long indexHigh = stack.pop();
        long indexLow = stack.pop();
        long baseHigh = stack.pop();
        long baseLow = stack.pop();
        long low = 0;
        long high = 0;
        for (int i = 0; i < 16; i++) {
            long index = get(i < 8 ? indexLow : indexHigh, i & 7, 8);
            long value =
                    index < 8
                            ? get(baseLow, (int) index, 8)
                            : index < 16 ? get(baseHigh, (int) index - 8, 8) : 0;
            if (i < 8) {
                low = put(low, i, 8, value);
            } else {
                high = put(high, i - 8, 8, value);
            }
        }
        stack.push(low);
        stack.push(high);
    }

    private static void extract(MStack stack, Instruction ins, int width, boolean signed) {
        int lane = (int) ins.operand(0);
        int offset = stack.size() - 2;
        long value =
                get(
                        stack.array()[lane < 64 / width ? offset : offset + 1],
                        lane % (64 / width),
                        width);
        if (signed) {
            value = signExtend(value, width);
        }
        stack.pop();
        stack.array()[stack.size() - 1] = value;
    }

    private static void replace(MStack stack, Instruction ins, int width) {
        long value = stack.pop();
        int lane = (int) ins.operand(0);
        int offset = stack.size() - 2;
        if (lane < 64 / width) {
            stack.array()[offset] = put(stack.array()[offset], lane, width, value);
        } else {
            stack.array()[offset + 1] =
                    put(stack.array()[offset + 1], lane - 64 / width, width, value);
        }
    }

    private static void bitwise(MStack stack, int operation) {
        long bHigh = stack.pop();
        long bLow = stack.pop();
        int offset = stack.size() - 2;
        long aLow = stack.array()[offset];
        long aHigh = stack.array()[offset + 1];
        stack.array()[offset] = bitwise(aLow, bLow, operation);
        stack.array()[offset + 1] = bitwise(aHigh, bHigh, operation);
    }

    private static long bitwise(long a, long b, int operation) {
        switch (operation) {
            case AND:
                return a & b;
            case ANDNOT:
                return a & ~b;
            case OR:
                return a | b;
            case XOR:
                return a ^ b;
            default:
                throw new AssertionError(operation);
        }
    }

    private static void bitselect(MStack stack) {
        long maskHigh = stack.pop();
        long maskLow = stack.pop();
        long secondHigh = stack.pop();
        long secondLow = stack.pop();
        int offset = stack.size() - 2;
        long firstLow = stack.array()[offset];
        long firstHigh = stack.array()[offset + 1];
        stack.array()[offset] = (firstLow & maskLow) | (secondLow & ~maskLow);
        stack.array()[offset + 1] = (firstHigh & maskHigh) | (secondHigh & ~maskHigh);
    }

    private static void anyTrue(MStack stack) {
        long high = stack.pop();
        long low = stack.pop();
        stack.push((low | high) == 0 ? BitOps.FALSE : BitOps.TRUE);
    }

    private static void binaryInt(MStack stack, int width, int operation) {
        long bHigh = stack.pop();
        long bLow = stack.pop();
        int offset = stack.size() - 2;
        long aLow = stack.array()[offset];
        long aHigh = stack.array()[offset + 1];
        long low = 0;
        long high = 0;
        int lanes = 64 / width;
        for (int i = 0; i < lanes; i++) {
            low = put(low, i, width, intLane(aLow, bLow, i, width, operation));
            high = put(high, i, width, intLane(aHigh, bHigh, i, width, operation));
        }
        stack.array()[offset] = low;
        stack.array()[offset + 1] = high;
    }

    private static long intLane(long aWord, long bWord, int lane, int width, int operation) {
        long aBits = get(aWord, lane, width);
        long bBits = get(bWord, lane, width);
        long a = signExtend(aBits, width);
        long b = signExtend(bBits, width);
        long au = unsigned(aBits, width);
        long bu = unsigned(bBits, width);
        switch (operation) {
            case ADD:
                return a + b;
            case SUB:
                return a - b;
            case MUL:
                return a * b;
            case MIN_S:
                return Math.min(a, b);
            case MIN_U:
                return Math.min(au, bu);
            case MAX_S:
                return Math.max(a, b);
            case MAX_U:
                return Math.max(au, bu);
            case EQ:
                return aBits == bBits ? mask(width) : 0;
            case NE:
                return aBits != bBits ? mask(width) : 0;
            case LT_S:
                return a < b ? mask(width) : 0;
            case LT_U:
                return compareUnsigned(au, bu, width) < 0 ? mask(width) : 0;
            case GT_S:
                return a > b ? mask(width) : 0;
            case GT_U:
                return compareUnsigned(au, bu, width) > 0 ? mask(width) : 0;
            case LE_S:
                return a <= b ? mask(width) : 0;
            case LE_U:
                return compareUnsigned(au, bu, width) <= 0 ? mask(width) : 0;
            case GE_S:
                return a >= b ? mask(width) : 0;
            case GE_U:
                return compareUnsigned(au, bu, width) >= 0 ? mask(width) : 0;
            case ADD_SAT_S:
                return saturate(a + b, width);
            case ADD_SAT_U:
                return saturateUnsigned(au + bu, width);
            case SUB_SAT_S:
                return saturate(a - b, width);
            case SUB_SAT_U:
                return Math.max(0, au - bu);
            case AVGR_U:
                return (au + bu + 1) >>> 1;
            case Q15MULR_SAT_S:
                return saturate((a * b + 16384) >> 15, 16);
            default:
                throw new AssertionError(operation);
        }
    }

    private static void shift(MStack stack, int width, int operation) {
        int shift = (int) stack.pop() & (width - 1);
        int offset = stack.size() - 2;
        for (int word = 0; word < 2; word++) {
            long value = stack.array()[offset + word];
            long result = 0;
            for (int lane = 0; lane < 64 / width; lane++) {
                long bits = get(value, lane, width);
                long signed = signExtend(bits, width);
                long shifted;
                switch (operation) {
                    case SHL:
                        shifted = bits << shift;
                        break;
                    case SHR_S:
                        shifted = signed >> shift;
                        break;
                    case SHR_U:
                        shifted = unsigned(bits, width) >>> shift;
                        break;
                    default:
                        throw new AssertionError(operation);
                }
                result = put(result, lane, width, shifted);
            }
            stack.array()[offset + word] = result;
        }
    }

    private static void unaryInt(MStack stack, int width, int operation) {
        int offset = stack.size() - 2;
        for (int word = 0; word < 2; word++) {
            long value = stack.array()[offset + word];
            long result = 0;
            for (int lane = 0; lane < 64 / width; lane++) {
                long bits = get(value, lane, width);
                long signed = signExtend(bits, width);
                long resultValue;
                switch (operation) {
                    case ABS:
                        resultValue = Math.abs(signed);
                        break;
                    case NEG:
                        resultValue = -signed;
                        break;
                    case POPCNT:
                        resultValue = Integer.bitCount((int) bits);
                        break;
                    default:
                        throw new AssertionError(operation);
                }
                result = put(result, lane, width, resultValue);
            }
            stack.array()[offset + word] = result;
        }
    }

    private static void allTrue(MStack stack, int width) {
        long high = stack.pop();
        long low = stack.pop();
        boolean result = true;
        for (int i = 0; i < 64 / width; i++) {
            result &= get(low, i, width) != 0;
            result &= get(high, i, width) != 0;
        }
        stack.push(result ? BitOps.TRUE : BitOps.FALSE);
    }

    private static void bitmask(MStack stack, int width) {
        long high = stack.pop();
        long low = stack.pop();
        long result = 0;
        int lanes = 128 / width;
        for (int i = 0; i < lanes; i++) {
            long value = get(i < 64 / width ? low : high, i % (64 / width), width);
            if ((value & (1L << (width - 1))) != 0) {
                result |= 1L << i;
            }
        }
        stack.push(result);
    }

    private static void binaryFloat(MStack stack, int width, int operation) {
        long bHigh = stack.pop();
        long bLow = stack.pop();
        int offset = stack.size() - 2;
        long aLow = stack.array()[offset];
        long aHigh = stack.array()[offset + 1];
        long low = 0;
        long high = 0;
        int lanes = 64 / width;
        for (int i = 0; i < lanes; i++) {
            low = put(low, i, width, floatLane(aLow, bLow, i, width, operation));
            high = put(high, i, width, floatLane(aHigh, bHigh, i, width, operation));
        }
        stack.array()[offset] = low;
        stack.array()[offset + 1] = high;
    }

    private static long floatLane(long aWord, long bWord, int lane, int width, int operation) {
        long aBits = get(aWord, lane, width);
        long bBits = get(bWord, lane, width);
        if (width == 32) {
            float a = Float.intBitsToFloat((int) aBits);
            float b = Float.intBitsToFloat((int) bBits);
            switch (operation) {
                case ADD:
                    return Float.floatToIntBits(a + b);
                case SUB:
                    return Float.floatToIntBits(a - b);
                case MUL:
                    return Float.floatToIntBits(a * b);
                case DIV:
                    return Float.floatToIntBits(a / b);
                case MIN:
                    return Float.floatToIntBits(Math.min(a, b));
                case MAX:
                    return Float.floatToIntBits(Math.max(a, b));
                case PMIN:
                    return Float.floatToRawIntBits(pmin(a, b));
                case PMAX:
                    return Float.floatToRawIntBits(pmax(a, b));
                default:
                    return floatCompare(a, b, operation) ? 0xffffffffL : 0;
            }
        }
        double a = Double.longBitsToDouble(aBits);
        double b = Double.longBitsToDouble(bBits);
        switch (operation) {
            case ADD:
                return Double.doubleToLongBits(a + b);
            case SUB:
                return Double.doubleToLongBits(a - b);
            case MUL:
                return Double.doubleToLongBits(a * b);
            case DIV:
                return Double.doubleToLongBits(a / b);
            case MIN:
                return Double.doubleToLongBits(Math.min(a, b));
            case MAX:
                return Double.doubleToLongBits(Math.max(a, b));
            case PMIN:
                return Double.doubleToRawLongBits(pmin(a, b));
            case PMAX:
                return Double.doubleToRawLongBits(pmax(a, b));
            default:
                return floatCompare(a, b, operation) ? -1L : 0;
        }
    }

    private static float pmin(float a, float b) {
        return b < a ? b : a;
    }

    private static double pmin(double a, double b) {
        return b < a ? b : a;
    }

    private static float pmax(float a, float b) {
        return a < b ? b : a;
    }

    private static double pmax(double a, double b) {
        return a < b ? b : a;
    }

    private static boolean floatCompare(float a, float b, int operation) {
        switch (operation) {
            case EQ:
                return a == b;
            case NE:
                return a != b;
            case LT:
                return a < b;
            case GT:
                return a > b;
            case LE:
                return a <= b;
            case GE:
                return a >= b;
            default:
                throw new AssertionError(operation);
        }
    }

    private static boolean floatCompare(double a, double b, int operation) {
        switch (operation) {
            case EQ:
                return a == b;
            case NE:
                return a != b;
            case LT:
                return a < b;
            case GT:
                return a > b;
            case LE:
                return a <= b;
            case GE:
                return a >= b;
            default:
                throw new AssertionError(operation);
        }
    }

    // Bit-preserving abs/neg/pmin/pmax; arithmetic operations canonicalize NaN.
    private static void unaryFloat(MStack stack, int width, int operation) {
        int offset = stack.size() - 2;
        for (int word = 0; word < 2; word++) {
            long value = stack.array()[offset + word];
            long result = 0;
            for (int lane = 0; lane < 64 / width; lane++) {
                long bits = get(value, lane, width);
                if (width == 32) {
                    long laneResult;
                    switch (operation) {
                        case ABS:
                            laneResult = bits & 0x7fffffffL;
                            break;
                        case NEG:
                            laneResult = bits ^ 0x80000000L;
                            break;
                        case SQRT:
                            laneResult =
                                    Float.floatToIntBits(
                                            (float) Math.sqrt(Float.intBitsToFloat((int) bits)));
                            break;
                        case CEIL:
                            laneResult =
                                    Float.floatToIntBits(
                                            (float) Math.ceil(Float.intBitsToFloat((int) bits)));
                            break;
                        case FLOOR:
                            laneResult =
                                    Float.floatToIntBits(
                                            (float) Math.floor(Float.intBitsToFloat((int) bits)));
                            break;
                        case TRUNC:
                            float x = Float.intBitsToFloat((int) bits);
                            laneResult =
                                    Float.floatToIntBits(
                                            (float) (x < 0 ? Math.ceil(x) : Math.floor(x)));
                            break;
                        case NEAREST:
                            laneResult =
                                    Float.floatToIntBits(
                                            (float) Math.rint(Float.intBitsToFloat((int) bits)));
                            break;
                        default:
                            throw new AssertionError(operation);
                    }
                    result = put(result, lane, width, laneResult);
                } else {
                    long laneResult;
                    switch (operation) {
                        case ABS:
                            laneResult = bits & 0x7fffffffffffffffL;
                            break;
                        case NEG:
                            laneResult = bits ^ 0x8000000000000000L;
                            break;
                        case SQRT:
                            laneResult =
                                    Double.doubleToLongBits(
                                            Math.sqrt(Double.longBitsToDouble(bits)));
                            break;
                        case CEIL:
                            laneResult =
                                    Double.doubleToLongBits(
                                            Math.ceil(Double.longBitsToDouble(bits)));
                            break;
                        case FLOOR:
                            laneResult =
                                    Double.doubleToLongBits(
                                            Math.floor(Double.longBitsToDouble(bits)));
                            break;
                        case TRUNC:
                            double x = Double.longBitsToDouble(bits);
                            laneResult =
                                    Double.doubleToLongBits(x < 0 ? Math.ceil(x) : Math.floor(x));
                            break;
                        case NEAREST:
                            laneResult =
                                    Double.doubleToLongBits(
                                            Math.rint(Double.longBitsToDouble(bits)));
                            break;
                        default:
                            throw new AssertionError(operation);
                    }
                    result = put(result, lane, width, laneResult);
                }
            }
            stack.array()[offset + word] = result;
        }
    }

    private static void narrow(MStack stack, int inputWidth, boolean signed) {
        long secondHigh = stack.pop();
        long secondLow = stack.pop();
        int offset = stack.size() - 2;
        long firstLow = stack.array()[offset];
        long firstHigh = stack.array()[offset + 1];
        int outputWidth = inputWidth / 2;
        long low = 0;
        long high = 0;
        int inputLanes = 128 / inputWidth;
        for (int i = 0; i < inputLanes; i++) {
            int secondLane = i + inputLanes;
            long firstValue =
                    narrowValue(vectorGet(firstLow, firstHigh, i, inputWidth), inputWidth, signed);
            long secondValue =
                    narrowValue(
                            vectorGet(secondLow, secondHigh, i, inputWidth), inputWidth, signed);
            if (i < 64 / outputWidth) {
                low = put(low, i, outputWidth, firstValue);
            } else {
                high = put(high, i - 64 / outputWidth, outputWidth, firstValue);
            }
            if (secondLane < 64 / outputWidth) {
                low = put(low, secondLane, outputWidth, secondValue);
            } else {
                high = put(high, secondLane - 64 / outputWidth, outputWidth, secondValue);
            }
        }
        stack.array()[offset] = low;
        stack.array()[offset + 1] = high;
    }

    private static long narrowValue(long bits, int width, boolean signed) {
        long value = signExtend(bits, width);
        if (!signed) {
            return Math.max(0, Math.min(mask(width / 2), value));
        }
        long min = -(1L << (width / 2 - 1));
        long max = (1L << (width / 2 - 1)) - 1;
        return Math.max(min, Math.min(max, value));
    }

    private static void extend(MStack stack, int inputWidth, boolean signed, boolean high) {
        long inputHigh = stack.pop();
        long inputLow = stack.pop();
        long source = high ? inputHigh : inputLow;
        int outputWidth = inputWidth * 2;
        long low = 0;
        long resultHigh = 0;
        int outputLanes = 128 / outputWidth;
        for (int i = 0; i < outputLanes; i++) {
            long bits = get(source, i, inputWidth);
            long value = signed ? signExtend(bits, inputWidth) : unsigned(bits, inputWidth);
            if (i < 64 / outputWidth) {
                low = put(low, i, outputWidth, value);
            } else {
                resultHigh = put(resultHigh, i - 64 / outputWidth, outputWidth, value);
            }
        }
        stack.push(low);
        stack.push(resultHigh);
    }

    private static void pairwise(MStack stack, int inputWidth, boolean signed) {
        long high = stack.pop();
        long low = stack.pop();
        int outputWidth = inputWidth * 2;
        long resultLow = 0;
        long resultHigh = 0;
        int count = 128 / inputWidth;
        for (int i = 0; i < count / 2; i++) {
            long first =
                    get(
                            i * 2 < 64 / inputWidth ? low : high,
                            i * 2 % (64 / inputWidth),
                            inputWidth);
            long second =
                    get(
                            i * 2 + 1 < 64 / inputWidth ? low : high,
                            (i * 2 + 1) % (64 / inputWidth),
                            inputWidth);
            long value =
                    signed
                            ? signExtend(first, inputWidth) + signExtend(second, inputWidth)
                            : unsigned(first, inputWidth) + unsigned(second, inputWidth);
            if (i < 64 / outputWidth) {
                resultLow = put(resultLow, i, outputWidth, value);
            } else {
                resultHigh = put(resultHigh, i - 64 / outputWidth, outputWidth, value);
            }
        }
        stack.push(resultLow);
        stack.push(resultHigh);
    }

    private static void extmul(MStack stack, int inputWidth, boolean signed, boolean high) {
        long secondHigh = stack.pop();
        long secondLow = stack.pop();
        int offset = stack.size() - 2;
        long firstLow = stack.array()[offset];
        long firstHigh = stack.array()[offset + 1];
        int outputWidth = inputWidth * 2;
        int lanes = 64 / inputWidth;
        int start = high ? 64 / inputWidth : 0;
        long low = 0;
        long resultHigh = 0;
        for (int i = 0; i < lanes; i++) {
            int lane = start + i;
            long aBits = vectorGet(firstLow, firstHigh, lane, inputWidth);
            long bBits = vectorGet(secondLow, secondHigh, lane, inputWidth);
            long value =
                    signed
                            ? signExtend(aBits, inputWidth) * signExtend(bBits, inputWidth)
                            : unsigned(aBits, inputWidth) * unsigned(bBits, inputWidth);
            if (i < 64 / outputWidth) {
                low = put(low, i, outputWidth, value);
            } else {
                resultHigh = put(resultHigh, i - 64 / outputWidth, outputWidth, value);
            }
        }
        stack.array()[offset] = low;
        stack.array()[offset + 1] = resultHigh;
    }

    private static void dot(MStack stack) {
        long secondHigh = stack.pop();
        long secondLow = stack.pop();
        int offset = stack.size() - 2;
        long firstLow = stack.array()[offset];
        long firstHigh = stack.array()[offset + 1];
        long low = 0;
        long high = 0;
        for (int i = 0; i < 4; i++) {
            int lane = 2 * i;
            long first =
                    signExtend(vectorGet(firstLow, firstHigh, lane, 16), 16)
                            * signExtend(vectorGet(secondLow, secondHigh, lane, 16), 16);
            long second =
                    signExtend(vectorGet(firstLow, firstHigh, lane + 1, 16), 16)
                            * signExtend(vectorGet(secondLow, secondHigh, lane + 1, 16), 16);
            long value = first + second;
            if (i < 2) {
                low = put(low, i, 32, value);
            } else {
                high = put(high, i - 2, 32, value);
            }
        }
        stack.array()[offset] = low;
        stack.array()[offset + 1] = high;
    }

    private static void q15(MStack stack) {
        binaryInt(stack, 16, Q15MULR_SAT_S);
    }

    private static void truncSatF32(MStack stack, boolean signed) {
        int offset = stack.size() - 2;
        for (int word = 0; word < 2; word++) {
            long input = stack.array()[offset + word];
            long result = 0;
            for (int i = 0; i < 2; i++) {
                float value = Float.intBitsToFloat((int) get(input, i, 32));
                long converted =
                        signed
                                ? OpcodeImpl.I32_TRUNC_SAT_F32_S(value)
                                : OpcodeImpl.I32_TRUNC_SAT_F32_U(value);
                result = put(result, i, 32, converted);
            }
            stack.array()[offset + word] = result;
        }
    }

    private static void truncSatF64Zero(MStack stack, boolean signed) {
        int offset = stack.size() - 2;
        long low = stack.array()[offset];
        long high = stack.array()[offset + 1];
        double first = Double.longBitsToDouble(low);
        double second = Double.longBitsToDouble(high);
        long resultLow =
                signed
                        ? OpcodeImpl.I32_TRUNC_SAT_F64_S(first)
                        : OpcodeImpl.I32_TRUNC_SAT_F64_U(first);
        long resultHigh =
                signed
                        ? OpcodeImpl.I32_TRUNC_SAT_F64_S(second)
                        : OpcodeImpl.I32_TRUNC_SAT_F64_U(second);
        stack.array()[offset] = (resultLow & 0xffffffffL) | ((resultHigh & 0xffffffffL) << 32);
        stack.array()[offset + 1] = 0;
    }

    private static void convertI32ToF32(MStack stack, boolean signed) {
        int offset = stack.size() - 2;
        for (int word = 0; word < 2; word++) {
            long input = stack.array()[offset + word];
            long result = 0;
            for (int i = 0; i < 2; i++) {
                int value = (int) get(input, i, 32);
                float converted =
                        signed
                                ? OpcodeImpl.F32_CONVERT_I32_S(value)
                                : OpcodeImpl.F32_CONVERT_I32_U(value);
                result = put(result, i, 32, Float.floatToIntBits(converted));
            }
            stack.array()[offset + word] = result;
        }
    }

    private static void convertLowI32ToF64(MStack stack, boolean signed) {
        int offset = stack.size() - 2;
        long input = stack.array()[offset];
        double first =
                signed ? (int) get(input, 0, 32) : Integer.toUnsignedLong((int) get(input, 0, 32));
        double second =
                signed ? (int) get(input, 1, 32) : Integer.toUnsignedLong((int) get(input, 1, 32));
        stack.array()[offset] = Double.doubleToLongBits(first);
        stack.array()[offset + 1] = Double.doubleToLongBits(second);
    }

    private static void demote(MStack stack) {
        long high = stack.pop();
        long low = stack.pop();
        stack.push(
                (Float.floatToIntBits((float) Double.longBitsToDouble(low)) & 0xffffffffL)
                        | ((long) Float.floatToIntBits((float) Double.longBitsToDouble(high))
                                << 32));
        stack.push(0);
    }

    private static void promote(MStack stack) {
        stack.pop();
        long low = stack.pop();
        stack.push(Double.doubleToLongBits(Float.intBitsToFloat((int) low)));
        stack.push(Double.doubleToLongBits(Float.intBitsToFloat((int) (low >>> 32))));
    }

    private static long get(long word, int lane, int width) {
        if (width == 64) {
            return word;
        }
        return (word >>> (lane * width)) & mask(width);
    }

    private static long vectorGet(long low, long high, int lane, int width) {
        return lane < 64 / width ? get(low, lane, width) : get(high, lane - 64 / width, width);
    }

    private static long put(long word, int lane, int width, long value) {
        if (width == 64) {
            return value;
        }
        long shift = (long) lane * width;
        long laneMask = mask(width) << shift;
        return (word & ~laneMask) | ((value & mask(width)) << shift);
    }

    private static long mask(int width) {
        return width == 64 ? -1L : (1L << width) - 1;
    }

    private static long unsigned(long value, int width) {
        return width == 64 ? value : value & mask(width);
    }

    private static long signExtend(long value, int width) {
        if (width == 64) {
            return value;
        }
        long mask = mask(width);
        long sign = 1L << (width - 1);
        value &= mask;
        return (value ^ sign) - sign;
    }

    private static int compareUnsigned(long a, long b, int width) {
        return width == 64 ? Long.compareUnsigned(a, b) : Long.compare(a, b);
    }

    private static long saturate(long value, int width) {
        long min = -(1L << (width - 1));
        long max = (1L << (width - 1)) - 1;
        return Math.max(min, Math.min(max, value));
    }

    private static long saturateUnsigned(long value, int width) {
        return Math.min(mask(width), value);
    }
}
