package run.endive.runtime.internal;

import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;
import run.endive.runtime.Instance;
import run.endive.runtime.MStack;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.types.Instruction;

/**
 * The interpreter's v128 ops, on the same {@link V128Words} helpers as compiled code.
 *
 * <p>Vectors use two stack longs: the low word is pushed first, and lane 0 occupies the low bits
 * of the low word. Lane memory operands are offset = operand(1), memory index = operand(2), and
 * lane index = operand(3); extract/replace use operand(0).
 */
public final class V128Ops {

    private V128Ops() {}

    private interface WordAndInt {
        long apply(long word, int n);
    }

    private interface WordsAndInt {
        long apply(long a, long b, int n);
    }

    private interface Test {
        int apply(long lo, long hi);
    }

    public static boolean eval(MStack stack, Instance instance, Instruction instruction) {
        switch (instruction.opcode()) {
            case V128_CONST:
                stack.push(instruction.operand(0));
                stack.push(instruction.operand(1));
                break;
            case V128_LOAD:
                {
                    int ptr = readMemPtr(stack, instruction);
                    var memory = memory(instance, instruction);
                    stack.push(memory.readLong(ptr));
                    stack.push(memory.readLong(ptr + 8));
                    break;
                }
            case V128_LOAD8x8_S:
                loadExtend(stack, instance, instruction, V128Words::I16x8_EXTEND_LOW_I8x16_S);
                break;
            case V128_LOAD8x8_U:
                loadExtend(stack, instance, instruction, V128Words::I16x8_EXTEND_LOW_I8x16_U);
                break;
            case V128_LOAD16x4_S:
                loadExtend(stack, instance, instruction, V128Words::I32x4_EXTEND_LOW_I16x8_S);
                break;
            case V128_LOAD16x4_U:
                loadExtend(stack, instance, instruction, V128Words::I32x4_EXTEND_LOW_I16x8_U);
                break;
            case V128_LOAD32x2_S:
                loadExtend(stack, instance, instruction, V128Words::I64x2_EXTEND_LOW_I32x4_S);
                break;
            case V128_LOAD32x2_U:
                loadExtend(stack, instance, instruction, V128Words::I64x2_EXTEND_LOW_I32x4_U);
                break;
            case V128_LOAD8_SPLAT:
                {
                    int ptr = readMemPtr(stack, instruction);
                    splat(stack, V128Words.I8x16_SPLAT(memory(instance, instruction).read(ptr)));
                    break;
                }
            case V128_LOAD16_SPLAT:
                {
                    int ptr = readMemPtr(stack, instruction);
                    splat(
                            stack,
                            V128Words.I16x8_SPLAT(memory(instance, instruction).readShort(ptr)));
                    break;
                }
            case V128_LOAD32_SPLAT:
                {
                    int ptr = readMemPtr(stack, instruction);
                    splat(stack, V128Words.I32x4_SPLAT(memory(instance, instruction).readInt(ptr)));
                    break;
                }
            case V128_LOAD64_SPLAT:
                {
                    int ptr = readMemPtr(stack, instruction);
                    splat(stack, memory(instance, instruction).readLong(ptr));
                    break;
                }
            case V128_LOAD32_ZERO:
                {
                    int ptr = readMemPtr(stack, instruction);
                    stack.push(memory(instance, instruction).readU32(ptr));
                    stack.push(0);
                    break;
                }
            case V128_LOAD64_ZERO:
                {
                    int ptr = readMemPtr(stack, instruction);
                    stack.push(memory(instance, instruction).readLong(ptr));
                    stack.push(0);
                    break;
                }
            case V128_LOAD8_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    int x = memory(instance, instruction).read(ptr);
                    int i = wordIndex(stack, lane / 8);
                    stack.array()[i] = V128Words.I8x16_REPLACE_LANE(stack.array()[i], lane % 8, x);
                    break;
                }
            case V128_LOAD16_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    int x = memory(instance, instruction).readShort(ptr);
                    int i = wordIndex(stack, lane / 4);
                    stack.array()[i] = V128Words.I16x8_REPLACE_LANE(stack.array()[i], lane % 4, x);
                    break;
                }
            case V128_LOAD32_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    int x = memory(instance, instruction).readInt(ptr);
                    int i = wordIndex(stack, lane / 2);
                    stack.array()[i] = V128Words.I32x4_REPLACE_LANE(stack.array()[i], lane % 2, x);
                    break;
                }
            case V128_LOAD64_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    long x = memory(instance, instruction).readLong(ptr);
                    stack.array()[wordIndex(stack, lane)] = x;
                    break;
                }
            case V128_STORE:
                {
                    long high = stack.pop();
                    long low = stack.pop();
                    int ptr = readMemPtr(stack, instruction);
                    var memory = memory(instance, instruction);
                    // high half first: if the store ends out of bounds it traps before writing
                    memory.writeLong(ptr + 8, high);
                    memory.writeLong(ptr, low);
                    break;
                }
            case V128_STORE8_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    long word = popWord(stack, lane / 8);
                    memory(instance, instruction)
                            .writeByte(ptr, (byte) V128Words.I8x16_EXTRACT_LANE_U(word, lane % 8));
                    break;
                }
            case V128_STORE16_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    long word = popWord(stack, lane / 4);
                    memory(instance, instruction)
                            .writeShort(
                                    ptr, (short) V128Words.I16x8_EXTRACT_LANE_U(word, lane % 4));
                    break;
                }
            case V128_STORE32_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    long word = popWord(stack, lane / 2);
                    memory(instance, instruction)
                            .writeI32(ptr, V128Words.I32x4_EXTRACT_LANE(word, lane % 2));
                    break;
                }
            case V128_STORE64_LANE:
                {
                    int lane = (int) instruction.operand(3);
                    int ptr = laneAddress(stack, instruction);
                    memory(instance, instruction).writeLong(ptr, popWord(stack, lane));
                    break;
                }
            case I8x16_SHUFFLE:
                shuffle(stack, instruction);
                break;
            case I8x16_SWIZZLE:
                {
                    long s1 = stack.pop();
                    long s0 = stack.pop();
                    long a1 = stack.pop();
                    long a0 = stack.pop();
                    stack.push(V128Words.I8x16_SWIZZLE(a0, a1, s0));
                    stack.push(V128Words.I8x16_SWIZZLE(a0, a1, s1));
                    break;
                }
            case V128_BITSELECT:
                {
                    long c1 = stack.pop();
                    long c0 = stack.pop();
                    long b1 = stack.pop();
                    long b0 = stack.pop();
                    long a1 = stack.pop();
                    long a0 = stack.pop();
                    stack.push(V128Words.V128_BITSELECT(a0, b0, c0));
                    stack.push(V128Words.V128_BITSELECT(a1, b1, c1));
                    break;
                }
            case I8x16_SPLAT:
                splat(stack, V128Words.I8x16_SPLAT((int) stack.pop()));
                break;
            case I16x8_SPLAT:
                splat(stack, V128Words.I16x8_SPLAT((int) stack.pop()));
                break;
            case I32x4_SPLAT:
            case F32x4_SPLAT:
                splat(stack, V128Words.I32x4_SPLAT((int) stack.pop()));
                break;
            case I64x2_SPLAT:
            case F64x2_SPLAT:
                splat(stack, stack.pop());
                break;
            case I8x16_EXTRACT_LANE_S:
                {
                    int lane = (int) instruction.operand(0);
                    stack.push(V128Words.I8x16_EXTRACT_LANE_S(popWord(stack, lane / 8), lane % 8));
                    break;
                }
            case I8x16_EXTRACT_LANE_U:
                {
                    int lane = (int) instruction.operand(0);
                    stack.push(V128Words.I8x16_EXTRACT_LANE_U(popWord(stack, lane / 8), lane % 8));
                    break;
                }
            case I16x8_EXTRACT_LANE_S:
                {
                    int lane = (int) instruction.operand(0);
                    stack.push(V128Words.I16x8_EXTRACT_LANE_S(popWord(stack, lane / 4), lane % 4));
                    break;
                }
            case I16x8_EXTRACT_LANE_U:
                {
                    int lane = (int) instruction.operand(0);
                    stack.push(V128Words.I16x8_EXTRACT_LANE_U(popWord(stack, lane / 4), lane % 4));
                    break;
                }
            case I32x4_EXTRACT_LANE:
            case F32x4_EXTRACT_LANE:
                {
                    int lane = (int) instruction.operand(0);
                    stack.push(V128Words.I32x4_EXTRACT_LANE(popWord(stack, lane / 2), lane % 2));
                    break;
                }
            case I64x2_EXTRACT_LANE:
            case F64x2_EXTRACT_LANE:
                stack.push(popWord(stack, (int) instruction.operand(0)));
                break;
            case I8x16_REPLACE_LANE:
                {
                    int x = (int) stack.pop();
                    int lane = (int) instruction.operand(0);
                    int i = wordIndex(stack, lane / 8);
                    stack.array()[i] = V128Words.I8x16_REPLACE_LANE(stack.array()[i], lane % 8, x);
                    break;
                }
            case I16x8_REPLACE_LANE:
                {
                    int x = (int) stack.pop();
                    int lane = (int) instruction.operand(0);
                    int i = wordIndex(stack, lane / 4);
                    stack.array()[i] = V128Words.I16x8_REPLACE_LANE(stack.array()[i], lane % 4, x);
                    break;
                }
            case I32x4_REPLACE_LANE:
            case F32x4_REPLACE_LANE:
                {
                    int x = (int) stack.pop();
                    int lane = (int) instruction.operand(0);
                    int i = wordIndex(stack, lane / 2);
                    stack.array()[i] = V128Words.I32x4_REPLACE_LANE(stack.array()[i], lane % 2, x);
                    break;
                }
            case I64x2_REPLACE_LANE:
            case F64x2_REPLACE_LANE:
                {
                    long x = stack.pop();
                    stack.array()[wordIndex(stack, (int) instruction.operand(0))] = x;
                    break;
                }
            case V128_NOT:
                unary(stack, a -> ~a);
                break;
            case V128_AND:
                binary(stack, (a, b) -> a & b);
                break;
            case V128_ANDNOT:
                binary(stack, (a, b) -> a & ~b);
                break;
            case V128_OR:
                binary(stack, (a, b) -> a | b);
                break;
            case V128_XOR:
                binary(stack, (a, b) -> a ^ b);
                break;
            case I64x2_ADD:
                binary(stack, (a, b) -> a + b);
                break;
            case I64x2_SUB:
                binary(stack, (a, b) -> a - b);
                break;
            case I64x2_MUL:
                binary(stack, (a, b) -> a * b);
                break;
            case I64x2_NEG:
                unary(stack, a -> -a);
                break;
            case I64x2_SHL:
                shift(stack, (a, n) -> a << n);
                break;
            case I64x2_SHR_S:
                shift(stack, (a, n) -> a >> n);
                break;
            case I64x2_SHR_U:
                shift(stack, (a, n) -> a >>> n);
                break;
            case I8x16_ABS:
                unary(stack, V128Words::I8x16_ABS);
                break;
            case I8x16_NEG:
                unary(stack, V128Words::I8x16_NEG);
                break;
            case I8x16_POPCNT:
                unary(stack, V128Words::I8x16_POPCNT);
                break;
            case I16x8_ABS:
                unary(stack, V128Words::I16x8_ABS);
                break;
            case I16x8_NEG:
                unary(stack, V128Words::I16x8_NEG);
                break;
            case I16x8_EXTADD_PAIRWISE_I8x16_S:
                unary(stack, V128Words::I16x8_EXTADD_PAIRWISE_I8x16_S);
                break;
            case I16x8_EXTADD_PAIRWISE_I8x16_U:
                unary(stack, V128Words::I16x8_EXTADD_PAIRWISE_I8x16_U);
                break;
            case I32x4_ABS:
                unary(stack, V128Words::I32x4_ABS);
                break;
            case I32x4_NEG:
                unary(stack, V128Words::I32x4_NEG);
                break;
            case I32x4_EXTADD_PAIRWISE_I16x8_S:
                unary(stack, V128Words::I32x4_EXTADD_PAIRWISE_I16x8_S);
                break;
            case I32x4_EXTADD_PAIRWISE_I16x8_U:
                unary(stack, V128Words::I32x4_EXTADD_PAIRWISE_I16x8_U);
                break;
            case I32x4_TRUNC_SAT_F32X4_S:
                unary(stack, V128Words::I32x4_TRUNC_SAT_F32X4_S);
                break;
            case I32x4_TRUNC_SAT_F32X4_U:
                unary(stack, V128Words::I32x4_TRUNC_SAT_F32X4_U);
                break;
            case I64x2_ABS:
                unary(stack, V128Words::I64x2_ABS);
                break;
            case F32x4_ABS:
                unary(stack, V128Words::F32x4_ABS);
                break;
            case F32x4_NEG:
                unary(stack, V128Words::F32x4_NEG);
                break;
            case F32x4_SQRT:
                unary(stack, V128Words::F32x4_SQRT);
                break;
            case F32x4_CEIL:
                unary(stack, V128Words::F32x4_CEIL);
                break;
            case F32x4_FLOOR:
                unary(stack, V128Words::F32x4_FLOOR);
                break;
            case F32x4_TRUNC:
                unary(stack, V128Words::F32x4_TRUNC);
                break;
            case F32x4_NEAREST:
                unary(stack, V128Words::F32x4_NEAREST);
                break;
            case F32x4_CONVERT_I32x4_S:
                unary(stack, V128Words::F32x4_CONVERT_I32x4_S);
                break;
            case F32x4_CONVERT_I32x4_U:
                unary(stack, V128Words::F32x4_CONVERT_I32x4_U);
                break;
            case F64x2_ABS:
                unary(stack, V128Words::F64x2_ABS);
                break;
            case F64x2_NEG:
                unary(stack, V128Words::F64x2_NEG);
                break;
            case F64x2_SQRT:
                unary(stack, V128Words::F64x2_SQRT);
                break;
            case F64x2_CEIL:
                unary(stack, V128Words::F64x2_CEIL);
                break;
            case F64x2_FLOOR:
                unary(stack, V128Words::F64x2_FLOOR);
                break;
            case F64x2_TRUNC:
                unary(stack, V128Words::F64x2_TRUNC);
                break;
            case F64x2_NEAREST:
                unary(stack, V128Words::F64x2_NEAREST);
                break;
            case I16x8_EXTEND_LOW_I8x16_S:
                unaryHalf(stack, 0, V128Words::I16x8_EXTEND_LOW_I8x16_S);
                break;
            case I16x8_EXTEND_LOW_I8x16_U:
                unaryHalf(stack, 0, V128Words::I16x8_EXTEND_LOW_I8x16_U);
                break;
            case I32x4_EXTEND_LOW_I16x8_S:
                unaryHalf(stack, 0, V128Words::I32x4_EXTEND_LOW_I16x8_S);
                break;
            case I32x4_EXTEND_LOW_I16x8_U:
                unaryHalf(stack, 0, V128Words::I32x4_EXTEND_LOW_I16x8_U);
                break;
            case I64x2_EXTEND_LOW_I32x4_S:
                unaryHalf(stack, 0, V128Words::I64x2_EXTEND_LOW_I32x4_S);
                break;
            case I64x2_EXTEND_LOW_I32x4_U:
                unaryHalf(stack, 0, V128Words::I64x2_EXTEND_LOW_I32x4_U);
                break;
            case F64x2_PROMOTE_LOW_F32x4:
                unaryHalf(stack, 0, V128Words::F64x2_PROMOTE_LOW_F32x4);
                break;
            case F64x2_CONVERT_LOW_I32x4_S:
                unaryHalf(stack, 0, V128Words::F64x2_CONVERT_LOW_I32x4_S);
                break;
            case F64x2_CONVERT_LOW_I32x4_U:
                unaryHalf(stack, 0, V128Words::F64x2_CONVERT_LOW_I32x4_U);
                break;
            case I16x8_EXTEND_HIGH_I8x16_S:
                unaryHalf(stack, 1, V128Words::I16x8_EXTEND_HIGH_I8x16_S);
                break;
            case I16x8_EXTEND_HIGH_I8x16_U:
                unaryHalf(stack, 1, V128Words::I16x8_EXTEND_HIGH_I8x16_U);
                break;
            case I32x4_EXTEND_HIGH_I16x8_S:
                unaryHalf(stack, 1, V128Words::I32x4_EXTEND_HIGH_I16x8_S);
                break;
            case I32x4_EXTEND_HIGH_I16x8_U:
                unaryHalf(stack, 1, V128Words::I32x4_EXTEND_HIGH_I16x8_U);
                break;
            case I64x2_EXTEND_HIGH_I32x4_S:
                unaryHalf(stack, 1, V128Words::I64x2_EXTEND_HIGH_I32x4_S);
                break;
            case I64x2_EXTEND_HIGH_I32x4_U:
                unaryHalf(stack, 1, V128Words::I64x2_EXTEND_HIGH_I32x4_U);
                break;
            case F32x4_DEMOTE_LOW_F64x2_ZERO:
                unaryZero(stack, V128Words::F32x4_DEMOTE_LOW_F64x2_ZERO);
                break;
            case I32x4_TRUNC_SAT_F64x2_S_ZERO:
                unaryZero(stack, V128Words::I32x4_TRUNC_SAT_F64x2_S_ZERO);
                break;
            case I32x4_TRUNC_SAT_F64x2_U_ZERO:
                unaryZero(stack, V128Words::I32x4_TRUNC_SAT_F64x2_U_ZERO);
                break;
            case I8x16_EQ:
                binary(stack, V128Words::I8x16_EQ);
                break;
            case I8x16_NE:
                binary(stack, V128Words::I8x16_NE);
                break;
            case I8x16_LT_S:
                binary(stack, V128Words::I8x16_LT_S);
                break;
            case I8x16_LT_U:
                binary(stack, V128Words::I8x16_LT_U);
                break;
            case I8x16_GT_S:
                binary(stack, V128Words::I8x16_GT_S);
                break;
            case I8x16_GT_U:
                binary(stack, V128Words::I8x16_GT_U);
                break;
            case I8x16_LE_S:
                binary(stack, V128Words::I8x16_LE_S);
                break;
            case I8x16_LE_U:
                binary(stack, V128Words::I8x16_LE_U);
                break;
            case I8x16_GE_S:
                binary(stack, V128Words::I8x16_GE_S);
                break;
            case I8x16_GE_U:
                binary(stack, V128Words::I8x16_GE_U);
                break;
            case I8x16_ADD:
                binary(stack, V128Words::I8x16_ADD);
                break;
            case I8x16_ADD_SAT_S:
                binary(stack, V128Words::I8x16_ADD_SAT_S);
                break;
            case I8x16_ADD_SAT_U:
                binary(stack, V128Words::I8x16_ADD_SAT_U);
                break;
            case I8x16_SUB:
                binary(stack, V128Words::I8x16_SUB);
                break;
            case I8x16_SUB_SAT_S:
                binary(stack, V128Words::I8x16_SUB_SAT_S);
                break;
            case I8x16_SUB_SAT_U:
                binary(stack, V128Words::I8x16_SUB_SAT_U);
                break;
            case I8x16_MIN_S:
                binary(stack, V128Words::I8x16_MIN_S);
                break;
            case I8x16_MIN_U:
                binary(stack, V128Words::I8x16_MIN_U);
                break;
            case I8x16_MAX_S:
                binary(stack, V128Words::I8x16_MAX_S);
                break;
            case I8x16_MAX_U:
                binary(stack, V128Words::I8x16_MAX_U);
                break;
            case I8x16_AVGR_U:
                binary(stack, V128Words::I8x16_AVGR_U);
                break;
            case I16x8_EQ:
                binary(stack, V128Words::I16x8_EQ);
                break;
            case I16x8_NE:
                binary(stack, V128Words::I16x8_NE);
                break;
            case I16x8_LT_S:
                binary(stack, V128Words::I16x8_LT_S);
                break;
            case I16x8_LT_U:
                binary(stack, V128Words::I16x8_LT_U);
                break;
            case I16x8_GT_S:
                binary(stack, V128Words::I16x8_GT_S);
                break;
            case I16x8_GT_U:
                binary(stack, V128Words::I16x8_GT_U);
                break;
            case I16x8_LE_S:
                binary(stack, V128Words::I16x8_LE_S);
                break;
            case I16x8_LE_U:
                binary(stack, V128Words::I16x8_LE_U);
                break;
            case I16x8_GE_S:
                binary(stack, V128Words::I16x8_GE_S);
                break;
            case I16x8_GE_U:
                binary(stack, V128Words::I16x8_GE_U);
                break;
            case I16x8_Q15MULR_SAT_S:
                binary(stack, V128Words::I16x8_Q15MULR_SAT_S);
                break;
            case I16x8_ADD:
                binary(stack, V128Words::I16x8_ADD);
                break;
            case I16x8_ADD_SAT_S:
                binary(stack, V128Words::I16x8_ADD_SAT_S);
                break;
            case I16x8_ADD_SAT_U:
                binary(stack, V128Words::I16x8_ADD_SAT_U);
                break;
            case I16x8_SUB:
                binary(stack, V128Words::I16x8_SUB);
                break;
            case I16x8_SUB_SAT_S:
                binary(stack, V128Words::I16x8_SUB_SAT_S);
                break;
            case I16x8_SUB_SAT_U:
                binary(stack, V128Words::I16x8_SUB_SAT_U);
                break;
            case I16x8_MUL:
                binary(stack, V128Words::I16x8_MUL);
                break;
            case I16x8_MIN_S:
                binary(stack, V128Words::I16x8_MIN_S);
                break;
            case I16x8_MIN_U:
                binary(stack, V128Words::I16x8_MIN_U);
                break;
            case I16x8_MAX_S:
                binary(stack, V128Words::I16x8_MAX_S);
                break;
            case I16x8_MAX_U:
                binary(stack, V128Words::I16x8_MAX_U);
                break;
            case I16x8_AVGR_U:
                binary(stack, V128Words::I16x8_AVGR_U);
                break;
            case I32x4_EQ:
                binary(stack, V128Words::I32x4_EQ);
                break;
            case I32x4_NE:
                binary(stack, V128Words::I32x4_NE);
                break;
            case I32x4_LT_S:
                binary(stack, V128Words::I32x4_LT_S);
                break;
            case I32x4_LT_U:
                binary(stack, V128Words::I32x4_LT_U);
                break;
            case I32x4_GT_S:
                binary(stack, V128Words::I32x4_GT_S);
                break;
            case I32x4_GT_U:
                binary(stack, V128Words::I32x4_GT_U);
                break;
            case I32x4_LE_S:
                binary(stack, V128Words::I32x4_LE_S);
                break;
            case I32x4_LE_U:
                binary(stack, V128Words::I32x4_LE_U);
                break;
            case I32x4_GE_S:
                binary(stack, V128Words::I32x4_GE_S);
                break;
            case I32x4_GE_U:
                binary(stack, V128Words::I32x4_GE_U);
                break;
            case I32x4_ADD:
                binary(stack, V128Words::I32x4_ADD);
                break;
            case I32x4_SUB:
                binary(stack, V128Words::I32x4_SUB);
                break;
            case I32x4_MUL:
                binary(stack, V128Words::I32x4_MUL);
                break;
            case I32x4_MIN_S:
                binary(stack, V128Words::I32x4_MIN_S);
                break;
            case I32x4_MIN_U:
                binary(stack, V128Words::I32x4_MIN_U);
                break;
            case I32x4_MAX_S:
                binary(stack, V128Words::I32x4_MAX_S);
                break;
            case I32x4_MAX_U:
                binary(stack, V128Words::I32x4_MAX_U);
                break;
            case I32x4_DOT_I16x8_S:
                binary(stack, V128Words::I32x4_DOT_I16x8_S);
                break;
            case I64x2_EQ:
                binary(stack, V128Words::I64x2_EQ);
                break;
            case I64x2_NE:
                binary(stack, V128Words::I64x2_NE);
                break;
            case I64x2_LT_S:
                binary(stack, V128Words::I64x2_LT_S);
                break;
            case I64x2_GT_S:
                binary(stack, V128Words::I64x2_GT_S);
                break;
            case I64x2_LE_S:
                binary(stack, V128Words::I64x2_LE_S);
                break;
            case I64x2_GE_S:
                binary(stack, V128Words::I64x2_GE_S);
                break;
            case F32x4_EQ:
                binary(stack, V128Words::F32x4_EQ);
                break;
            case F32x4_NE:
                binary(stack, V128Words::F32x4_NE);
                break;
            case F32x4_LT:
                binary(stack, V128Words::F32x4_LT);
                break;
            case F32x4_GT:
                binary(stack, V128Words::F32x4_GT);
                break;
            case F32x4_LE:
                binary(stack, V128Words::F32x4_LE);
                break;
            case F32x4_GE:
                binary(stack, V128Words::F32x4_GE);
                break;
            case F32x4_ADD:
                binary(stack, V128Words::F32x4_ADD);
                break;
            case F32x4_SUB:
                binary(stack, V128Words::F32x4_SUB);
                break;
            case F32x4_MUL:
                binary(stack, V128Words::F32x4_MUL);
                break;
            case F32x4_DIV:
                binary(stack, V128Words::F32x4_DIV);
                break;
            case F32x4_MIN:
                binary(stack, V128Words::F32x4_MIN);
                break;
            case F32x4_MAX:
                binary(stack, V128Words::F32x4_MAX);
                break;
            case F32x4_PMIN:
                binary(stack, V128Words::F32x4_PMIN);
                break;
            case F32x4_PMAX:
                binary(stack, V128Words::F32x4_PMAX);
                break;
            case F64x2_EQ:
                binary(stack, V128Words::F64x2_EQ);
                break;
            case F64x2_NE:
                binary(stack, V128Words::F64x2_NE);
                break;
            case F64x2_LT:
                binary(stack, V128Words::F64x2_LT);
                break;
            case F64x2_GT:
                binary(stack, V128Words::F64x2_GT);
                break;
            case F64x2_LE:
                binary(stack, V128Words::F64x2_LE);
                break;
            case F64x2_GE:
                binary(stack, V128Words::F64x2_GE);
                break;
            case F64x2_ADD:
                binary(stack, V128Words::F64x2_ADD);
                break;
            case F64x2_SUB:
                binary(stack, V128Words::F64x2_SUB);
                break;
            case F64x2_MUL:
                binary(stack, V128Words::F64x2_MUL);
                break;
            case F64x2_DIV:
                binary(stack, V128Words::F64x2_DIV);
                break;
            case F64x2_MIN:
                binary(stack, V128Words::F64x2_MIN);
                break;
            case F64x2_MAX:
                binary(stack, V128Words::F64x2_MAX);
                break;
            case F64x2_PMIN:
                binary(stack, V128Words::F64x2_PMIN);
                break;
            case F64x2_PMAX:
                binary(stack, V128Words::F64x2_PMAX);
                break;
            case I16x8_EXTMUL_LOW_I8x16_S:
                binaryHalf(stack, 0, V128Words::I16x8_EXTMUL_LOW_I8x16_S);
                break;
            case I16x8_EXTMUL_LOW_I8x16_U:
                binaryHalf(stack, 0, V128Words::I16x8_EXTMUL_LOW_I8x16_U);
                break;
            case I32x4_EXTMUL_LOW_I16x8_S:
                binaryHalf(stack, 0, V128Words::I32x4_EXTMUL_LOW_I16x8_S);
                break;
            case I32x4_EXTMUL_LOW_I16x8_U:
                binaryHalf(stack, 0, V128Words::I32x4_EXTMUL_LOW_I16x8_U);
                break;
            case I64x2_EXTMUL_LOW_I32x4_S:
                binaryHalf(stack, 0, V128Words::I64x2_EXTMUL_LOW_I32x4_S);
                break;
            case I64x2_EXTMUL_LOW_I32x4_U:
                binaryHalf(stack, 0, V128Words::I64x2_EXTMUL_LOW_I32x4_U);
                break;
            case I16x8_EXTMUL_HIGH_I8x16_S:
                binaryHalf(stack, 1, V128Words::I16x8_EXTMUL_HIGH_I8x16_S);
                break;
            case I16x8_EXTMUL_HIGH_I8x16_U:
                binaryHalf(stack, 1, V128Words::I16x8_EXTMUL_HIGH_I8x16_U);
                break;
            case I32x4_EXTMUL_HIGH_I16x8_S:
                binaryHalf(stack, 1, V128Words::I32x4_EXTMUL_HIGH_I16x8_S);
                break;
            case I32x4_EXTMUL_HIGH_I16x8_U:
                binaryHalf(stack, 1, V128Words::I32x4_EXTMUL_HIGH_I16x8_U);
                break;
            case I64x2_EXTMUL_HIGH_I32x4_S:
                binaryHalf(stack, 1, V128Words::I64x2_EXTMUL_HIGH_I32x4_S);
                break;
            case I64x2_EXTMUL_HIGH_I32x4_U:
                binaryHalf(stack, 1, V128Words::I64x2_EXTMUL_HIGH_I32x4_U);
                break;
            case I8x16_NARROW_I16x8_S:
                narrow(stack, V128Words::I8x16_NARROW_I16x8_S);
                break;
            case I8x16_NARROW_I16x8_U:
                narrow(stack, V128Words::I8x16_NARROW_I16x8_U);
                break;
            case I16x8_NARROW_I32x4_S:
                narrow(stack, V128Words::I16x8_NARROW_I32x4_S);
                break;
            case I16x8_NARROW_I32x4_U:
                narrow(stack, V128Words::I16x8_NARROW_I32x4_U);
                break;
            case I8x16_SHL:
                shift(stack, V128Words::I8x16_SHL);
                break;
            case I8x16_SHR_S:
                shift(stack, V128Words::I8x16_SHR_S);
                break;
            case I8x16_SHR_U:
                shift(stack, V128Words::I8x16_SHR_U);
                break;
            case I16x8_SHL:
                shift(stack, V128Words::I16x8_SHL);
                break;
            case I16x8_SHR_S:
                shift(stack, V128Words::I16x8_SHR_S);
                break;
            case I16x8_SHR_U:
                shift(stack, V128Words::I16x8_SHR_U);
                break;
            case I32x4_SHL:
                shift(stack, V128Words::I32x4_SHL);
                break;
            case I32x4_SHR_S:
                shift(stack, V128Words::I32x4_SHR_S);
                break;
            case I32x4_SHR_U:
                shift(stack, V128Words::I32x4_SHR_U);
                break;
            case V128_ANY_TRUE:
                test(stack, V128Words::V128_ANY_TRUE);
                break;
            case I8x16_ALL_TRUE:
                test(stack, V128Words::I8x16_ALL_TRUE);
                break;
            case I8x16_BITMASK:
                test(stack, V128Words::I8x16_BITMASK);
                break;
            case I16x8_ALL_TRUE:
                test(stack, V128Words::I16x8_ALL_TRUE);
                break;
            case I16x8_BITMASK:
                test(stack, V128Words::I16x8_BITMASK);
                break;
            case I32x4_ALL_TRUE:
                test(stack, V128Words::I32x4_ALL_TRUE);
                break;
            case I32x4_BITMASK:
                test(stack, V128Words::I32x4_BITMASK);
                break;
            case I64x2_ALL_TRUE:
                test(stack, V128Words::I64x2_ALL_TRUE);
                break;
            case I64x2_BITMASK:
                test(stack, V128Words::I64x2_BITMASK);
                break;
            default:
                return false;
        }
        return true;
    }

    static int readMemPtr(MStack stack, Instruction ins) {
        int address = (int) stack.pop();
        if (ins.operand(1) < 0 || ins.operand(1) >= Integer.MAX_VALUE || address < 0) {
            throw new WasmRuntimeException("out of bounds memory access");
        }
        return (int) (ins.operand(1) + address);
    }

    private static Memory memory(Instance instance, Instruction ins) {
        return instance.memory((int) ins.operand(2));
    }

    // [address, v] -> [v], and the address of the lane load or store
    private static int laneAddress(MStack stack, Instruction ins) {
        long high = stack.pop();
        long low = stack.pop();
        int ptr = readMemPtr(stack, ins);
        stack.push(low);
        stack.push(high);
        return ptr;
    }

    // the stack index of word 0 or 1 of the v128 on top
    private static int wordIndex(MStack stack, int word) {
        return stack.size() - 2 + word;
    }

    // [v] -> [], and word 0 or 1 of v
    private static long popWord(MStack stack, int word) {
        long value = stack.array()[wordIndex(stack, word)];
        stack.pop();
        stack.pop();
        return value;
    }

    // [] -> [word, word]
    private static void splat(MStack stack, long word) {
        stack.push(word);
        stack.push(word);
    }

    // [address] -> [the 8 bytes at address widened by extend]
    private static void loadExtend(
            MStack stack, Instance instance, Instruction ins, WordAndInt extend) {
        int ptr = readMemPtr(stack, ins);
        long word = memory(instance, ins).readLong(ptr);
        stack.push(extend.apply(word, 0));
        stack.push(extend.apply(word, 1));
    }

    // [a, b] -> [the 16 bytes of a and b that the immediate picks]
    private static void shuffle(MStack stack, Instruction ins) {
        long b1 = stack.pop();
        long b0 = stack.pop();
        long a1 = stack.pop();
        long a0 = stack.pop();
        stack.push(shuffleWord(a0, a1, b0, b1, ins.operand(0)));
        stack.push(shuffleWord(a0, a1, b0, b1, ins.operand(1)));
    }

    private static long shuffleWord(long a0, long a1, long b0, long b1, long lanes) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            int lane = (int) (lanes >>> (i * 8)) & 0x1F;
            long word = lane < 16 ? (lane < 8 ? a0 : a1) : (lane < 24 ? b0 : b1);
            result |= ((word >>> ((lane & 7) * 8)) & 0xFF) << (i * 8);
        }
        return result;
    }

    // [a] -> [op(a)], word by word
    private static void unary(MStack stack, LongUnaryOperator op) {
        long[] words = stack.array();
        int i = wordIndex(stack, 0);
        words[i] = op.applyAsLong(words[i]);
        words[i + 1] = op.applyAsLong(words[i + 1]);
    }

    // [a, b] -> [op(a, b)], word by word
    private static void binary(MStack stack, LongBinaryOperator op) {
        long b1 = stack.pop();
        long b0 = stack.pop();
        long[] words = stack.array();
        int i = wordIndex(stack, 0);
        words[i] = op.applyAsLong(words[i], b0);
        words[i + 1] = op.applyAsLong(words[i + 1], b1);
    }

    // [a, n] -> [op(a, n)], word by word
    private static void shift(MStack stack, WordAndInt op) {
        int n = (int) stack.pop();
        long[] words = stack.array();
        int i = wordIndex(stack, 0);
        words[i] = op.apply(words[i], n);
        words[i + 1] = op.apply(words[i + 1], n);
    }

    // [a] -> [op(half of a, 0), op(half of a, 1)]
    private static void unaryHalf(MStack stack, int half, WordAndInt op) {
        long[] words = stack.array();
        int i = wordIndex(stack, 0);
        long a = words[i + half];
        words[i] = op.apply(a, 0);
        words[i + 1] = op.apply(a, 1);
    }

    // [a, b] -> [op(half of a, half of b, 0), op(half of a, half of b, 1)]
    private static void binaryHalf(MStack stack, int half, WordsAndInt op) {
        long b = popWord(stack, half);
        long[] words = stack.array();
        int i = wordIndex(stack, 0);
        long a = words[i + half];
        words[i] = op.apply(a, b, 0);
        words[i + 1] = op.apply(a, b, 1);
    }

    // [a, b] -> [op(a), op(b)]: each vector narrows to one word
    private static void narrow(MStack stack, LongBinaryOperator op) {
        long b1 = stack.pop();
        long b0 = stack.pop();
        long[] words = stack.array();
        int i = wordIndex(stack, 0);
        words[i] = op.applyAsLong(words[i], words[i + 1]);
        words[i + 1] = op.applyAsLong(b0, b1);
    }

    // [a] -> [op(a), 0]
    private static void unaryZero(MStack stack, LongBinaryOperator op) {
        long[] words = stack.array();
        int i = wordIndex(stack, 0);
        words[i] = op.applyAsLong(words[i], words[i + 1]);
        words[i + 1] = 0;
    }

    // [a] -> [op(a)], an i32
    private static void test(MStack stack, Test op) {
        long high = stack.pop();
        long low = stack.pop();
        stack.push(op.apply(low, high));
    }
}
