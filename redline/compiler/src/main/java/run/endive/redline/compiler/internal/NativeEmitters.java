package run.endive.redline.compiler.internal;

import run.endive.redline.api.internal.CtxBuffer;
import run.endive.wasm.types.AnnotatedInstruction;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.TableImport;
import run.endive.wasm.types.ValType;

/**
 * Static methods that emit Cranelift IR for each opcode category.
 * No control flow logic — these are pure opcode handlers that pop
 * operands from the value stack, call bridge exports, and push results.
 */
final class NativeEmitters {

    private NativeEmitters() {}

    // --- Constants ---

    static void emitI32Const(EmitContext ctx, AnnotatedInstruction ins) {
        ctx.valueStack.push(ctx.bridge.exports().emitIconst32((int) ins.operands()[0]));
    }

    static void emitI64Const(EmitContext ctx, AnnotatedInstruction ins) {
        long val = ins.operands()[0];
        ctx.valueStack.push(ctx.bridge.exports().emitIconst64((int) val, (int) (val >>> 32)));
    }

    static void emitF32Const(EmitContext ctx, AnnotatedInstruction ins) {
        ctx.valueStack.push(ctx.bridge.exports().emitF32const((int) ins.operands()[0]));
    }

    static void emitF64Const(EmitContext ctx, AnnotatedInstruction ins) {
        long bits = ins.operands()[0];
        ctx.valueStack.push(ctx.bridge.exports().emitF64const((int) bits, (int) (bits >>> 32)));
    }

    // --- i32 Arithmetic ---

    static void emitI32BinaryOp(EmitContext ctx, int op) {
        int b = ctx.valueStack.pop();
        int a = ctx.valueStack.pop();
        int result;
        switch (op) {
            case 0:
                result = ctx.bridge.exports().emitIadd(a, b);
                break;
            case 1:
                result = ctx.bridge.exports().emitIsub(a, b);
                break;
            case 2:
                result = ctx.bridge.exports().emitImul(a, b);
                break;
            case 3:
                result = ctx.bridge.exports().emitBand(a, b);
                break;
            case 4:
                result = ctx.bridge.exports().emitBor(a, b);
                break;
            case 5:
                result = ctx.bridge.exports().emitBxor(a, b);
                break;
            case 6:
                result = ctx.bridge.exports().emitIshl(a, b);
                break;
            case 7:
                result = ctx.bridge.exports().emitSshr(a, b);
                break;
            case 8:
                result = ctx.bridge.exports().emitUshr(a, b);
                break;
            case 9:
                result = ctx.bridge.exports().emitRotl(a, b);
                break;
            case 10:
                result = ctx.bridge.exports().emitRotr(a, b);
                break;
            default:
                throw new IllegalArgumentException("Unknown i32 binary op: " + op);
        }
        ctx.valueStack.push(result);
    }

    static void emitI32UnaryOp(EmitContext ctx, int op) {
        int val = ctx.valueStack.pop();
        int result;
        switch (op) {
            case 0:
                result = ctx.bridge.exports().emitClz(val);
                break;
            case 1:
                result = ctx.bridge.exports().emitCtz(val);
                break;
            case 2:
                result = ctx.bridge.exports().emitPopcnt(val);
                break;
            case 3:
                result = ctx.bridge.exports().emitEqz(val);
                break;
            default:
                throw new IllegalArgumentException("Unknown i32 unary op: " + op);
        }
        ctx.valueStack.push(result);
    }

    // --- i64 Arithmetic ---

    static void emitI64UnaryOp(EmitContext ctx, int op) {
        int val = ctx.valueStack.pop();
        int result;
        switch (op) {
            case 0:
                result = ctx.bridge.exports().emitClz(val);
                break;
            case 1:
                result = ctx.bridge.exports().emitCtz(val);
                break;
            case 2:
                result = ctx.bridge.exports().emitPopcnt(val);
                break;
            case 3:
                result = ctx.bridge.exports().emitEqzI64(val);
                break;
            default:
                throw new IllegalArgumentException("Unknown i64 unary op: " + op);
        }
        ctx.valueStack.push(result);
    }

    // --- Comparisons (i32 and i64 share emitIcmp) ---

    static void emitIcmp(EmitContext ctx, int cmpCode) {
        int b = ctx.valueStack.pop();
        int a = ctx.valueStack.pop();
        ctx.valueStack.push(ctx.bridge.exports().emitIcmp(cmpCode, a, b));
    }

    // --- Float Arithmetic ---

    static void emitFloatBinaryOp(EmitContext ctx, int op) {
        int b = ctx.valueStack.pop();
        int a = ctx.valueStack.pop();
        int result;
        switch (op) {
            case 0:
                result = ctx.bridge.exports().emitFadd(a, b);
                break;
            case 1:
                result = ctx.bridge.exports().emitFsub(a, b);
                break;
            case 2:
                result = ctx.bridge.exports().emitFmul(a, b);
                break;
            case 3:
                result = ctx.bridge.exports().emitFdiv(a, b);
                break;
            case 4:
                result = ctx.bridge.exports().emitFmin(a, b);
                break;
            case 5:
                result = ctx.bridge.exports().emitFmax(a, b);
                break;
            case 6:
                result = ctx.bridge.exports().emitFcopysign(a, b);
                break;
            default:
                throw new IllegalArgumentException("Unknown float binary op: " + op);
        }
        ctx.valueStack.push(result);
    }

    static void emitFloatUnaryOp(EmitContext ctx, int op) {
        int val = ctx.valueStack.pop();
        int result;
        switch (op) {
            case 0:
                result = ctx.bridge.exports().emitFabs(val);
                break;
            case 1:
                result = ctx.bridge.exports().emitFneg(val);
                break;
            case 2:
                result = ctx.bridge.exports().emitCeil(val);
                break;
            case 3:
                result = ctx.bridge.exports().emitFloor(val);
                break;
            case 4:
                result = ctx.bridge.exports().emitTruncFloat(val);
                break;
            case 5:
                result = ctx.bridge.exports().emitNearest(val);
                break;
            case 6:
                result = ctx.bridge.exports().emitSqrt(val);
                break;
            default:
                throw new IllegalArgumentException("Unknown float unary op: " + op);
        }
        ctx.valueStack.push(result);
    }

    // --- Float Comparisons ---

    static void emitFcmp(EmitContext ctx, int cmpCode) {
        int b = ctx.valueStack.pop();
        int a = ctx.valueStack.pop();
        ctx.valueStack.push(ctx.bridge.exports().emitFcmp(cmpCode, a, b));
    }

    // --- Safe Division (with trap pre-checks) ---

    static void emitSafeDiv(EmitContext ctx, boolean signed, boolean isRem, boolean is64) {
        int divisor = ctx.valueStack.pop();
        int dividend = ctx.valueStack.pop();

        int trapBlockZero = ctx.bridge.exports().createBlock();
        int safeBlock;

        int zero =
                is64
                        ? ctx.bridge.exports().emitIconst64(0, 0)
                        : ctx.bridge.exports().emitIconst32(0);
        int isZero = ctx.bridge.exports().emitIcmp(0, divisor, zero); // EQ

        if (signed && !isRem) {
            int checkOverflow = ctx.bridge.exports().createBlock();
            safeBlock = ctx.bridge.exports().createBlock();
            ctx.bridge.exports().emitBrif(isZero, trapBlockZero, checkOverflow);

            ctx.bridge.exports().switchToBlock(checkOverflow);
            int intMin;
            int negOne;
            if (is64) {
                intMin = ctx.bridge.exports().emitIconst64(0, 0x80000000);
                negOne = ctx.bridge.exports().emitIconst64(-1, -1);
            } else {
                intMin = ctx.bridge.exports().emitIconst32(0x80000000);
                negOne = ctx.bridge.exports().emitIconst32(-1);
            }
            int isMin = ctx.bridge.exports().emitIcmp(0, dividend, intMin);
            int isNeg1 = ctx.bridge.exports().emitIcmp(0, divisor, negOne);
            int both = ctx.bridge.exports().emitBand(isMin, isNeg1);

            int trapBlockOverflow = ctx.bridge.exports().createBlock();
            ctx.bridge.exports().emitBrif(both, trapBlockOverflow, safeBlock);

            fillTrapBlock(ctx, trapBlockOverflow, CtxBuffer.TRAP_INT_OVERFLOW);
        } else {
            safeBlock = ctx.bridge.exports().createBlock();
            ctx.bridge.exports().emitBrif(isZero, trapBlockZero, safeBlock);
        }

        fillTrapBlock(ctx, trapBlockZero, CtxBuffer.TRAP_DIV_BY_ZERO);

        ctx.bridge.exports().switchToBlock(safeBlock);
        int result;
        if (signed) {
            result =
                    isRem
                            ? ctx.bridge.exports().emitSrem(dividend, divisor)
                            : ctx.bridge.exports().emitSdiv(dividend, divisor);
        } else {
            result =
                    isRem
                            ? ctx.bridge.exports().emitUrem(dividend, divisor)
                            : ctx.bridge.exports().emitUdiv(dividend, divisor);
        }
        ctx.valueStack.push(result);
    }

    // --- Safe Float Truncation ---

    static void emitSafeTrunc(EmitContext ctx, int targetType, int sourceType, boolean signed) {
        int fval = ctx.valueStack.pop();

        int satResult =
                signed
                        ? ctx.bridge.exports().emitFcvtToSintSat(targetType, fval)
                        : ctx.bridge.exports().emitFcvtToUintSat(targetType, fval);

        // NaN check: fcmp NE x,x → true if NaN
        int isNan = ctx.bridge.exports().emitFcmp(1, fval, fval);
        int nanTrapBlock = ctx.bridge.exports().createBlock();
        int rangeCheckBlock = ctx.bridge.exports().createBlock();
        ctx.bridge.exports().emitBrif(isNan, nanTrapBlock, rangeCheckBlock);
        fillTrapBlock(ctx, nanTrapBlock, CtxBuffer.TRAP_TRUNC_NAN);

        // Range check: trap if value is out of representable integer range
        ctx.bridge.exports().switchToBlock(rangeCheckBlock);
        int tooHigh = emitTruncUpperCheck(ctx, fval, targetType, sourceType, signed);
        int tooLow = emitTruncLowerCheck(ctx, fval, targetType, sourceType, signed);
        int outOfRange = ctx.bridge.exports().emitBor(tooHigh, tooLow);
        int overflowTrapBlock = ctx.bridge.exports().createBlock();
        int okBlock = ctx.bridge.exports().createBlock();
        ctx.bridge.exports().emitBrif(outOfRange, overflowTrapBlock, okBlock);

        fillTrapBlock(ctx, overflowTrapBlock, CtxBuffer.TRAP_TRUNC_OVERFLOW);

        ctx.bridge.exports().switchToBlock(okBlock);
        ctx.valueStack.push(satResult);
    }

    // Emit: fval >= upperBound (GE=5)
    private static int emitTruncUpperCheck(
            EmitContext ctx, int fval, int targetType, int sourceType, boolean signed) {
        if (sourceType == 2 /* F32 */) {
            float bound;
            if (targetType == 0 /* I32 */) {
                bound = signed ? 2147483648.0f : 4294967296.0f;
            } else {
                bound = signed ? 9223372036854775808.0f : 18446744073709551616.0f;
            }
            int c = ctx.bridge.exports().emitF32const(Float.floatToRawIntBits(bound));
            return ctx.bridge.exports().emitFcmp(5, fval, c); // GE
        } else {
            double bound;
            if (targetType == 0 /* I32 */) {
                bound = signed ? 2147483648.0 : 4294967296.0;
            } else {
                bound = signed ? 9223372036854775808.0 : 18446744073709551616.0;
            }
            long bits = Double.doubleToRawLongBits(bound);
            int c = ctx.bridge.exports().emitF64const((int) bits, (int) (bits >>> 32));
            return ctx.bridge.exports().emitFcmp(5, fval, c); // GE
        }
    }

    // Emit: fval <= lowerBound (LE=4)
    private static int emitTruncLowerCheck(
            EmitContext ctx, int fval, int targetType, int sourceType, boolean signed) {
        if (sourceType == 2 /* F32 */) {
            float bound;
            if (signed) {
                // For signed: trap if val < MIN (use LE with MIN - 1 equivalent)
                // i32: -2147483648.0f is exactly representable, trap if val < it
                // i64: -9223372036854775808.0f is exactly representable
                bound = targetType == 0 /* I32 */ ? -2147483904.0f : -9223373136366403584.0f;
                // Use LE: trap if fval <= bound (since bound is just below MIN)
            } else {
                bound = -1.0f;
            }
            int c = ctx.bridge.exports().emitF32const(Float.floatToRawIntBits(bound));
            return ctx.bridge.exports().emitFcmp(4, fval, c); // LE
        } else {
            double bound;
            if (signed) {
                bound = targetType == 0 /* I32 */ ? -2147483649.0 : -9223372036854777856.0;
            } else {
                bound = -1.0;
            }
            long bits = Double.doubleToRawLongBits(bound);
            int c = ctx.bridge.exports().emitF64const((int) bits, (int) (bits >>> 32));
            return ctx.bridge.exports().emitFcmp(4, fval, c); // LE
        }
    }

    // --- Trap helper ---

    static void fillTrapBlock(EmitContext ctx, int trapBlock, int trapCode) {
        ctx.bridge.exports().switchToBlock(trapBlock);
        int ctxVal = ctx.bridge.exports().useVar(ctx.ctxPtrVar);
        int zero = ctx.bridge.exports().emitIconst32(0);
        int code = ctx.bridge.exports().emitIconst32(trapCode);
        ctx.bridge.exports().emitStoreI32(ctxVal, zero, code, CtxBuffer.TRAP_CODE);
        ctx.emitReturnForFuncType();
    }

    // --- Extensions ---

    static void emitI32Extend8S(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitSextend832(ctx.valueStack.pop()));
    }

    static void emitI32Extend16S(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitSextend1632(ctx.valueStack.pop()));
    }

    static void emitI64ExtendI32S(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitSextendI64(ctx.valueStack.pop()));
    }

    static void emitI64ExtendI32U(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitUextendI64(ctx.valueStack.pop()));
    }

    static void emitI64Extend8S(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitSextend864(ctx.valueStack.pop()));
    }

    static void emitI64Extend16S(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitSextend1664(ctx.valueStack.pop()));
    }

    static void emitI64Extend32S(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitSextend3264(ctx.valueStack.pop()));
    }

    static void emitI32WrapI64(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitI32WrapI64(ctx.valueStack.pop()));
    }

    // --- Reload memBase after calls (callee may have done memory.grow) ---

    private static void reloadMemBase(EmitContext ctx) {
        int zero = ctx.bridge.exports().emitIconst32(0);
        int newMemBase =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.MEM_BASE_ADDR);
        ctx.bridge.exports().defVar(ctx.memBaseVar, newMemBase);
    }

    // --- Memory bounds check ---

    private static final int[] LOAD_ACCESS_SIZE = {4, 8, 4, 8, 1, 1, 2, 2, 1, 1, 2, 2, 4, 4};
    private static final int[] STORE_ACCESS_SIZE = {4, 8, 4, 8, 1, 2, 1, 2, 4};

    /**
     * Emit a bounds check: if addr + offset + accessSize > memPages * 65536,
     * trap with OOB. Single compare + branch, predicted not-taken.
     */
    private static void emitBoundsCheck(EmitContext ctx, int addr, int offset, int accessSize) {
        // Compute effective end address as i64 to avoid i32 overflow
        int addr64 = ctx.bridge.exports().emitUextendI64(addr);
        long endOffset = Integer.toUnsignedLong(offset) + accessSize;
        int end =
                ctx.bridge
                        .exports()
                        .emitIadd(
                                addr64,
                                ctx.bridge
                                        .exports()
                                        .emitIconst64(
                                                (int) (endOffset & 0xFFFFFFFFL),
                                                (int) (endOffset >>> 32)));

        // Load memory size in bytes: memPages * 65536
        int zero = ctx.bridge.exports().emitIconst32(0);
        int memPages =
                ctx.bridge
                        .exports()
                        .emitLoadI32(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.MEMORY_PAGES);
        int memPages64 = ctx.bridge.exports().emitUextendI64(memPages);
        int memSize =
                ctx.bridge.exports().emitIshl(memPages64, ctx.bridge.exports().emitIconst64(16, 0));

        // if end > memSize → trap
        int oob = ctx.bridge.exports().emitIcmp(5, end, memSize); // GT unsigned
        int trapBlock = ctx.bridge.exports().createBlock();
        int okBlock = ctx.bridge.exports().createBlock();
        ctx.bridge.exports().emitBrif(oob, trapBlock, okBlock);

        fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_OOB);

        ctx.bridge.exports().switchToBlock(okBlock);
    }

    // --- Memory loads ---

    static void emitLoad(EmitContext ctx, AnnotatedInstruction ins, int loadType) {
        int addr = ctx.valueStack.pop();
        int offset = (int) ins.operands()[1];
        emitBoundsCheck(ctx, addr, offset, LOAD_ACCESS_SIZE[loadType]);
        int memBase = ctx.bridge.exports().useVar(ctx.memBaseVar);
        int result;
        switch (loadType) {
            case 0:
                result = ctx.bridge.exports().emitLoadI32(memBase, addr, offset);
                break;
            case 1:
                result = ctx.bridge.exports().emitLoadI64(memBase, addr, offset);
                break;
            case 2:
                result = ctx.bridge.exports().emitLoadF32(memBase, addr, offset);
                break;
            case 3:
                result = ctx.bridge.exports().emitLoadF64(memBase, addr, offset);
                break;
            case 4:
                result = ctx.bridge.exports().emitLoad8u(memBase, addr, offset);
                break;
            case 5:
                result = ctx.bridge.exports().emitLoad8s(memBase, addr, offset);
                break;
            case 6:
                result = ctx.bridge.exports().emitLoad16u(memBase, addr, offset);
                break;
            case 7:
                result = ctx.bridge.exports().emitLoad16s(memBase, addr, offset);
                break;
            case 8:
                result = ctx.bridge.exports().emitLoad8uI64(memBase, addr, offset);
                break;
            case 9:
                result = ctx.bridge.exports().emitLoad8sI64(memBase, addr, offset);
                break;
            case 10:
                result = ctx.bridge.exports().emitLoad16uI64(memBase, addr, offset);
                break;
            case 11:
                result = ctx.bridge.exports().emitLoad16sI64(memBase, addr, offset);
                break;
            case 12:
                result = ctx.bridge.exports().emitLoad32uI64(memBase, addr, offset);
                break;
            case 13:
                result = ctx.bridge.exports().emitLoad32sI64(memBase, addr, offset);
                break;
            default:
                throw new IllegalArgumentException("Unknown load type: " + loadType);
        }
        ctx.valueStack.push(result);
    }

    // --- Memory stores ---

    static void emitStore(EmitContext ctx, AnnotatedInstruction ins, int storeType) {
        int value = ctx.valueStack.pop();
        int addr = ctx.valueStack.pop();
        int offset = (int) ins.operands()[1];
        emitBoundsCheck(ctx, addr, offset, STORE_ACCESS_SIZE[storeType]);
        int memBase = ctx.bridge.exports().useVar(ctx.memBaseVar);
        switch (storeType) {
            case 0:
                ctx.bridge.exports().emitStoreI32(memBase, addr, value, offset);
                break;
            case 1:
                ctx.bridge.exports().emitStoreI64(memBase, addr, value, offset);
                break;
            case 2:
                ctx.bridge.exports().emitStoreF32(memBase, addr, value, offset);
                break;
            case 3:
                ctx.bridge.exports().emitStoreF64(memBase, addr, value, offset);
                break;
            case 4:
                ctx.bridge.exports().emitStore8(memBase, addr, value, offset);
                break;
            case 5:
                ctx.bridge.exports().emitStore16(memBase, addr, value, offset);
                break;
            case 6:
                ctx.bridge.exports().emitStore8I64(memBase, addr, value, offset);
                break;
            case 7:
                ctx.bridge.exports().emitStore16I64(memBase, addr, value, offset);
                break;
            case 8:
                ctx.bridge.exports().emitStore32I64(memBase, addr, value, offset);
                break;
            default:
                throw new IllegalArgumentException("Unknown store type: " + storeType);
        }
    }

    // --- Locals ---

    static void emitLocalGet(EmitContext ctx, AnnotatedInstruction ins) {
        ctx.valueStack.push(ctx.bridge.exports().useVar(ctx.localVars[(int) ins.operands()[0]]));
    }

    static void emitLocalSet(EmitContext ctx, AnnotatedInstruction ins) {
        int val = ctx.valueStack.pop();
        ctx.bridge.exports().defVar(ctx.localVars[(int) ins.operands()[0]], val);
    }

    static void emitLocalTee(EmitContext ctx, AnnotatedInstruction ins) {
        int val = ctx.valueStack.peek();
        ctx.bridge.exports().defVar(ctx.localVars[(int) ins.operands()[0]], val);
    }

    // --- Select ---

    static void emitSelect(EmitContext ctx) {
        int cond = ctx.valueStack.pop();
        int val2 = ctx.valueStack.pop();
        int val1 = ctx.valueStack.pop();
        ctx.valueStack.push(ctx.bridge.exports().emitSelect(cond, val1, val2));
    }

    // --- Globals ---

    static void emitGlobalGet(EmitContext ctx, AnnotatedInstruction ins) {
        int globalIdx = (int) ins.operands()[0];
        int zero = ctx.bridge.exports().emitIconst32(0);
        int globalsPtr =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.GLOBALS_PTR);
        int offsetVal = ctx.bridge.exports().emitIconst32(globalIdx * 8);
        int rawVal = ctx.bridge.exports().emitLoadI64(globalsPtr, offsetVal, 0);
        ValType globalType = ctx.resolveGlobalType(globalIdx);
        ctx.valueStack.push(ctx.narrowFromI64ForType(rawVal, globalType));
    }

    static void emitGlobalSet(EmitContext ctx, AnnotatedInstruction ins) {
        int globalIdx = (int) ins.operands()[0];
        int value = ctx.valueStack.pop();
        ValType globalType = ctx.resolveGlobalType(globalIdx);
        int widened = ctx.widenToI64ForType(value, globalType);
        int zero = ctx.bridge.exports().emitIconst32(0);
        int globalsPtr =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.GLOBALS_PTR);
        int offsetVal = ctx.bridge.exports().emitIconst32(globalIdx * 8);
        ctx.bridge.exports().emitStoreI64(globalsPtr, offsetVal, widened, 0);
    }

    // --- Memory operations ---

    static void emitMemorySize(EmitContext ctx) {
        int zero = ctx.bridge.exports().emitIconst32(0);
        int pages =
                ctx.bridge
                        .exports()
                        .emitLoadI32(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.MEMORY_PAGES);
        ctx.valueStack.push(pages);
    }

    static void emitMemoryGrow(EmitContext ctx) {
        int delta = ctx.valueStack.pop();
        int zero = ctx.bridge.exports().emitIconst32(0);
        ctx.bridge
                .exports()
                .emitStoreI32(
                        ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                        zero,
                        delta,
                        CtxBuffer.MEM_GROW_DELTA);
        int memGrowPtr =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.MEM_GROW_PTR);
        int growSig = ctx.getOrCreateTrampolineSigRef();
        ctx.bridge.exports().pushCallArg(ctx.bridge.exports().useVar(ctx.ctxPtrVar));
        int rawResult = ctx.bridge.exports().emitCallIndirect(growSig, memGrowPtr);
        int result = ctx.bridge.exports().emitIreduceI32(rawResult);
        ctx.valueStack.push(result);
        int newMemBase =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.MEM_BASE_ADDR);
        ctx.bridge.exports().defVar(ctx.memBaseVar, newMemBase);
    }

    // --- Unreachable ---

    static void emitUnreachable(EmitContext ctx) {
        int ctxVal = ctx.bridge.exports().useVar(ctx.ctxPtrVar);
        int zero = ctx.bridge.exports().emitIconst32(0);
        int code = ctx.bridge.exports().emitIconst32(CtxBuffer.TRAP_UNREACHABLE);
        ctx.bridge.exports().emitStoreI32(ctxVal, zero, code, CtxBuffer.TRAP_CODE);
        ctx.emitReturnForFuncType();
    }

    // --- Conversions ---

    static void emitTruncSat(EmitContext ctx, int targetType, boolean signed) {
        int val = ctx.valueStack.pop();
        ctx.valueStack.push(
                signed
                        ? ctx.bridge.exports().emitFcvtToSintSat(targetType, val)
                        : ctx.bridge.exports().emitFcvtToUintSat(targetType, val));
    }

    static void emitConvertFloat(EmitContext ctx, int targetType, boolean signed) {
        int val = ctx.valueStack.pop();
        ctx.valueStack.push(
                signed
                        ? ctx.bridge.exports().emitFcvtFromSint(targetType, val)
                        : ctx.bridge.exports().emitFcvtFromUint(targetType, val));
    }

    static void emitFpromote(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitFpromote(ctx.valueStack.pop()));
    }

    static void emitFdemote(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitFdemote(ctx.valueStack.pop()));
    }

    static void emitBitcastI32ToF32(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitBitcastI32ToF32(ctx.valueStack.pop()));
    }

    static void emitBitcastF32ToI32(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitBitcastF32ToI32(ctx.valueStack.pop()));
    }

    static void emitBitcastI64ToF64(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitBitcastI64ToF64(ctx.valueStack.pop()));
    }

    static void emitBitcastF64ToI64(EmitContext ctx) {
        ctx.valueStack.push(ctx.bridge.exports().emitBitcastF64ToI64(ctx.valueStack.pop()));
    }

    // --- Calls ---

    static void emitCall(EmitContext ctx, AnnotatedInstruction ins) {
        int targetFuncId = (int) ins.operands()[0];
        FunctionType targetType = ctx.resolveCallTargetType(targetFuncId);

        boolean calleeMultiReturn = targetType.returns().size() > 1;
        int sigRef =
                calleeMultiReturn
                        ? ctx.getOrCreateMultiReturnSigRef(targetType)
                        : ctx.getOrCreateSigRef(targetType);

        int argCount = targetType.params().size();
        int[] argVals = new int[argCount];
        for (int i = argCount - 1; i >= 0; i--) {
            argVals[i] = ctx.valueStack.pop();
        }

        int zero = ctx.bridge.exports().emitIconst32(0);
        ctx.bridge
                .exports()
                .emitStoreI32(
                        ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                        zero,
                        ctx.bridge.exports().emitIconst32(argCount),
                        CtxBuffer.ARG_COUNT);
        int argsPtr =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.ARGS_PTR);
        for (int i = 0; i < argCount; i++) {
            int widened = ctx.widenToI64(argVals[i], targetType.params().get(i));
            ctx.bridge.exports().emitStoreI64(argsPtr, zero, widened, CtxBuffer.argOffset(i));
        }

        int funcTablePtr =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.FUNC_TABLE_PTR);
        int funcIdOffset = ctx.bridge.exports().emitIconst32(targetFuncId * 8);
        int funcPtr = ctx.bridge.exports().emitLoadI64(funcTablePtr, funcIdOffset, 0);

        ctx.bridge.exports().pushCallArg(ctx.bridge.exports().useVar(ctx.memBaseVar));
        ctx.bridge.exports().pushCallArg(ctx.bridge.exports().useVar(ctx.ctxPtrVar));
        for (int i = 0; i < argCount; i++) {
            ctx.bridge.exports().pushCallArg(argVals[i]);
        }

        int rawResult = ctx.bridge.exports().emitCallIndirect(sigRef, funcPtr);

        if (calleeMultiReturn) {
            // Read return values from argsBuffer
            int zero2 = ctx.bridge.exports().emitIconst32(0);
            int argsPtr2 =
                    ctx.bridge
                            .exports()
                            .emitLoadI64(
                                    ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                    zero2,
                                    CtxBuffer.ARGS_PTR);
            for (int i = 0; i < targetType.returns().size(); i++) {
                int raw = ctx.bridge.exports().emitLoadI64(argsPtr2, zero2, CtxBuffer.argOffset(i));
                ctx.valueStack.push(ctx.narrowFromI64ForType(raw, targetType.returns().get(i)));
            }
        } else if (!targetType.returns().isEmpty()) {
            ctx.valueStack.push(rawResult);
        }

        // Reload memBase — callee may have called memory.grow
        reloadMemBase(ctx);
    }

    static void emitCallIndirect(EmitContext ctx, AnnotatedInstruction ins) {
        int typeId = (int) ins.operands()[0];
        int tableIdx = (int) ins.operands()[1];
        FunctionType targetType = (FunctionType) ctx.module.typeSection().getType(typeId);

        // Pop table element index (i32)
        int elemIdx = ctx.valueStack.pop();

        // Pop call arguments
        int argCount = targetType.params().size();
        int[] argVals = new int[argCount];
        for (int i = argCount - 1; i >= 0; i--) {
            argVals[i] = ctx.valueStack.pop();
        }

        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);
        int ctxPtr = b.useVar(ctx.ctxPtrVar);

        // 1. Load tablePtr from TABLE_PTRS[tableIdx]
        int tablePtrsPtr = b.emitLoadI64(ctxPtr, zero, CtxBuffer.TABLE_PTRS);
        int tableOffset = b.emitIconst32(tableIdx * 8);
        int tablePtr = b.emitLoadI64(tablePtrsPtr, tableOffset, 0);

        // 2. Bounds check: elemIdx >= table.size → trap "undefined element"
        int tableSize = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);
        int oobCheck = b.emitIcmp(9, b.emitUextendI64(elemIdx), b.emitUextendI64(tableSize));
        int trapOobBlock = b.createBlock();
        int afterOobBlock = b.createBlock();
        b.emitBrif(oobCheck, trapOobBlock, afterOobBlock);
        fillTrapBlock(ctx, trapOobBlock, CtxBuffer.TRAP_UNDEFINED_ELEMENT);
        b.switchToBlock(afterOobBlock);

        // 3. Calculate entry offset: elemIdx * 16 (TABLE_ENTRY_SIZE)
        int entryOffset = b.emitImul(elemIdx, b.emitIconst32(CtxBuffer.TABLE_ENTRY_SIZE));

        // 4. Load funcPtr from entry: tablePtr[ENTRIES_OFFSET + entryOffset + FUNC_PTR_OFFSET]
        int funcPtr =
                b.emitLoadI64(
                        tablePtr,
                        entryOffset,
                        CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_FUNC_PTR_OFFSET);

        // 5. Null check: funcPtr == 0 → trap
        int zero64 = b.emitIconst64(0, 0);
        int isNull = b.emitIcmp(0, funcPtr, zero64); // EQ
        int trapNullBlock = b.createBlock();
        int afterNullBlock = b.createBlock();
        b.emitBrif(isNull, trapNullBlock, afterNullBlock);
        fillTrapBlock(ctx, trapNullBlock, CtxBuffer.TRAP_UNINITIALIZED_ELEMENT);
        b.switchToBlock(afterNullBlock);

        // 6. Type check: entry.typeIdx == expectedCanonicalType
        int actualType =
                b.emitLoadI32(
                        tablePtr,
                        entryOffset,
                        CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_TYPE_IDX_OFFSET);
        int canonicalTypeId = ctx.canonicalTypeMap[typeId];
        int expectedType = b.emitIconst32(canonicalTypeId);
        int typeMismatch =
                b.emitIcmp(1, b.emitUextendI64(actualType), b.emitUextendI64(expectedType)); // NE
        int trapTypeBlock = b.createBlock();
        int afterTypeBlock = b.createBlock();
        b.emitBrif(typeMismatch, trapTypeBlock, afterTypeBlock);
        fillTrapBlock(ctx, trapTypeBlock, CtxBuffer.TRAP_INDIRECT_CALL_TYPE_MISMATCH);
        b.switchToBlock(afterTypeBlock);

        // 7. Determine if callee might be multi-return (>1 return)
        boolean calleeMultiReturn = targetType.returns().size() > 1;

        // Write args to argsBuffer (for import stubs that read from it)
        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        int zero2 = b.emitIconst32(0);
        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar), zero2, b.emitIconst32(argCount), CtxBuffer.ARG_COUNT);
        for (int i = 0; i < argCount; i++) {
            int widened = ctx.widenToI64(argVals[i], targetType.params().get(i));
            b.emitStoreI64(argsPtr, zero2, widened, CtxBuffer.argOffset(i));
        }

        // 8. Build SigRef and call (funcPtr loaded directly from table entry)
        int sigRef;
        if (calleeMultiReturn) {
            sigRef = ctx.getOrCreateMultiReturnSigRef(targetType);
        } else {
            sigRef = ctx.getOrCreateSigRef(targetType);
        }

        b.pushCallArg(b.useVar(ctx.memBaseVar));
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        for (int i = 0; i < argCount; i++) {
            b.pushCallArg(argVals[i]);
        }

        int rawResult = b.emitCallIndirect(sigRef, funcPtr);

        // 9. Handle results
        if (calleeMultiReturn) {
            int zero3 = b.emitIconst32(0);
            int argsPtr2 = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero3, CtxBuffer.ARGS_PTR);
            for (int i = 0; i < targetType.returns().size(); i++) {
                int raw = b.emitLoadI64(argsPtr2, zero3, CtxBuffer.argOffset(i));
                ctx.valueStack.push(ctx.narrowFromI64ForType(raw, targetType.returns().get(i)));
            }
        } else if (!targetType.returns().isEmpty()) {
            ctx.valueStack.push(rawResult);
        }

        // Reload memBase — callee may have called memory.grow
        reloadMemBase(ctx);
    }

    // --- Tail calls (return_call / return_call_indirect) ---

    static void emitReturnCall(EmitContext ctx, AnnotatedInstruction ins) {
        int targetFuncId = (int) ins.operands()[0];
        FunctionType targetType = ctx.resolveCallTargetType(targetFuncId);

        boolean calleeMultiReturn = targetType.returns().size() > 1;
        int sigRef =
                calleeMultiReturn
                        ? ctx.getOrCreateMultiReturnSigRef(targetType)
                        : ctx.getOrCreateSigRef(targetType);

        int argCount = targetType.params().size();
        int[] argVals = new int[argCount];
        for (int i = argCount - 1; i >= 0; i--) {
            argVals[i] = ctx.valueStack.pop();
        }

        int zero = ctx.bridge.exports().emitIconst32(0);
        ctx.bridge
                .exports()
                .emitStoreI32(
                        ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                        zero,
                        ctx.bridge.exports().emitIconst32(argCount),
                        CtxBuffer.ARG_COUNT);
        int argsPtr =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.ARGS_PTR);
        for (int i = 0; i < argCount; i++) {
            int widened = ctx.widenToI64(argVals[i], targetType.params().get(i));
            ctx.bridge.exports().emitStoreI64(argsPtr, zero, widened, CtxBuffer.argOffset(i));
        }

        int funcTablePtr =
                ctx.bridge
                        .exports()
                        .emitLoadI64(
                                ctx.bridge.exports().useVar(ctx.ctxPtrVar),
                                zero,
                                CtxBuffer.FUNC_TABLE_PTR);
        int funcIdOffset = ctx.bridge.exports().emitIconst32(targetFuncId * 8);
        int funcPtr = ctx.bridge.exports().emitLoadI64(funcTablePtr, funcIdOffset, 0);

        ctx.bridge.exports().pushCallArg(ctx.bridge.exports().useVar(ctx.memBaseVar));
        ctx.bridge.exports().pushCallArg(ctx.bridge.exports().useVar(ctx.ctxPtrVar));
        for (int i = 0; i < argCount; i++) {
            ctx.bridge.exports().pushCallArg(argVals[i]);
        }

        ctx.bridge.exports().emitReturnCallIndirect(sigRef, funcPtr);
    }

    static void emitReturnCallIndirect(EmitContext ctx, AnnotatedInstruction ins) {
        int typeId = (int) ins.operands()[0];
        int tableIdx = (int) ins.operands()[1];
        FunctionType targetType = (FunctionType) ctx.module.typeSection().getType(typeId);

        int elemIdx = ctx.valueStack.pop();

        int argCount = targetType.params().size();
        int[] argVals = new int[argCount];
        for (int i = argCount - 1; i >= 0; i--) {
            argVals[i] = ctx.valueStack.pop();
        }

        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);
        int ctxPtr = b.useVar(ctx.ctxPtrVar);

        int tablePtrsPtr = b.emitLoadI64(ctxPtr, zero, CtxBuffer.TABLE_PTRS);
        int tableOffset = b.emitIconst32(tableIdx * 8);
        int tablePtr = b.emitLoadI64(tablePtrsPtr, tableOffset, 0);

        int tableSize = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);
        int oobCheck = b.emitIcmp(9, b.emitUextendI64(elemIdx), b.emitUextendI64(tableSize));
        int trapOobBlock = b.createBlock();
        int afterOobBlock = b.createBlock();
        b.emitBrif(oobCheck, trapOobBlock, afterOobBlock);
        fillTrapBlock(ctx, trapOobBlock, CtxBuffer.TRAP_UNDEFINED_ELEMENT);
        b.switchToBlock(afterOobBlock);

        int entryOffset = b.emitImul(elemIdx, b.emitIconst32(CtxBuffer.TABLE_ENTRY_SIZE));

        int funcPtr =
                b.emitLoadI64(
                        tablePtr,
                        entryOffset,
                        CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_FUNC_PTR_OFFSET);

        int zero64 = b.emitIconst64(0, 0);
        int isNull = b.emitIcmp(0, funcPtr, zero64);
        int trapNullBlock = b.createBlock();
        int afterNullBlock = b.createBlock();
        b.emitBrif(isNull, trapNullBlock, afterNullBlock);
        fillTrapBlock(ctx, trapNullBlock, CtxBuffer.TRAP_UNINITIALIZED_ELEMENT);
        b.switchToBlock(afterNullBlock);

        int actualType =
                b.emitLoadI32(
                        tablePtr,
                        entryOffset,
                        CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_TYPE_IDX_OFFSET);
        int canonicalTypeId = ctx.canonicalTypeMap[typeId];
        int expectedType = b.emitIconst32(canonicalTypeId);
        int typeMismatch =
                b.emitIcmp(1, b.emitUextendI64(actualType), b.emitUextendI64(expectedType));
        int trapTypeBlock = b.createBlock();
        int afterTypeBlock = b.createBlock();
        b.emitBrif(typeMismatch, trapTypeBlock, afterTypeBlock);
        fillTrapBlock(ctx, trapTypeBlock, CtxBuffer.TRAP_INDIRECT_CALL_TYPE_MISMATCH);
        b.switchToBlock(afterTypeBlock);

        boolean calleeMultiReturn = targetType.returns().size() > 1;

        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        int zero2 = b.emitIconst32(0);
        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar), zero2, b.emitIconst32(argCount), CtxBuffer.ARG_COUNT);
        for (int i = 0; i < argCount; i++) {
            int widened = ctx.widenToI64(argVals[i], targetType.params().get(i));
            b.emitStoreI64(argsPtr, zero2, widened, CtxBuffer.argOffset(i));
        }

        int sigRef;
        if (calleeMultiReturn) {
            sigRef = ctx.getOrCreateMultiReturnSigRef(targetType);
        } else {
            sigRef = ctx.getOrCreateSigRef(targetType);
        }

        b.pushCallArg(b.useVar(ctx.memBaseVar));
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        for (int i = 0; i < argCount; i++) {
            b.pushCallArg(argVals[i]);
        }

        b.emitReturnCallIndirect(sigRef, funcPtr);
    }

    // --- Table operations (fully native, no trampoline) ---

    private static int loadTablePtr(EmitContext ctx, int tableIdx) {
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);
        int tablePtrsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TABLE_PTRS);
        int offset = b.emitIconst32(tableIdx * 8);
        return b.emitLoadI64(tablePtrsPtr, offset, 0);
    }

    private static boolean isExternRefTable(EmitContext ctx, int tableIdx) {
        int importTableCount = 0;
        for (var imp :
                ctx.module.importSection().stream().collect(java.util.stream.Collectors.toList())) {
            if (imp.importType() == ExternalType.TABLE) {
                if (importTableCount == tableIdx) {
                    return ((TableImport) imp).entryType().equals(ValType.ExternRef);
                }
                importTableCount++;
            }
        }
        int definedIdx = tableIdx - importTableCount;
        return ctx.module
                .tableSection()
                .getTable(definedIdx)
                .elementType()
                .equals(ValType.ExternRef);
    }

    static void emitTableGet(EmitContext ctx, AnnotatedInstruction ins) {
        int tableIdx = (int) ins.operands()[0];
        int index = ctx.valueStack.pop();
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int tablePtr = loadTablePtr(ctx, tableIdx);
        int tableSize = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);

        // Bounds check: index >= size → trap
        int oob = b.emitIcmp(9, b.emitUextendI64(index), b.emitUextendI64(tableSize));
        int trapBlock = b.createBlock();
        int okBlock = b.createBlock();
        b.emitBrif(oob, trapBlock, okBlock);
        fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_TABLE_OOB);
        b.switchToBlock(okBlock);

        // Load funcId from 16-byte entry: entry.funcId at offset +4
        int entryOffset = b.emitImul(index, b.emitIconst32(CtxBuffer.TABLE_ENTRY_SIZE));
        int ref =
                b.emitLoadI32(
                        tablePtr,
                        entryOffset,
                        CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
        // Sign-extend so REF_NULL_VALUE (-1 as i32) stays -1 as i64.
        ctx.valueStack.push(b.emitSextendI64(ref));
    }

    static void emitTableSet(EmitContext ctx, AnnotatedInstruction ins) {
        int tableIdx = (int) ins.operands()[0];
        // Value is i64 on stack (ref type), narrow to i32 for table storage
        int funcId = ctx.bridge.exports().emitIreduceI32(ctx.valueStack.pop());
        int index = ctx.valueStack.pop();
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int tablePtr = loadTablePtr(ctx, tableIdx);
        int tableSize = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);

        // Bounds check
        int oob = b.emitIcmp(9, b.emitUextendI64(index), b.emitUextendI64(tableSize));
        int trapBlock = b.createBlock();
        int okBlock = b.createBlock();
        b.emitBrif(oob, trapBlock, okBlock);
        fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_TABLE_OOB);
        b.switchToBlock(okBlock);

        // Calculate entry offset for 16-byte entries
        int entryOffset = b.emitImul(index, b.emitIconst32(CtxBuffer.TABLE_ENTRY_SIZE));
        int entryTypeOff = CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_TYPE_IDX_OFFSET;
        int entryFuncIdOff = CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_FUNC_ID_OFFSET;
        int entryFuncPtrOff = CtxBuffer.TABLE_ENTRIES_OFFSET + CtxBuffer.ENTRY_FUNC_PTR_OFFSET;

        // Check if value is REF_NULL (-1)
        int refNull = b.emitIconst32(-1);
        int isNull = b.emitIcmp(0, b.emitUextendI64(funcId), b.emitUextendI64(refNull)); // EQ

        int nullBlock = b.createBlock();
        int nonNullBlock = b.createBlock();
        int mergeBlock = b.createBlock();
        b.emitBrif(isNull, nullBlock, nonNullBlock);

        // Null path: write null entry
        b.switchToBlock(nullBlock);
        b.emitStoreI32(tablePtr, entryOffset, b.emitIconst32(0), entryTypeOff);
        b.emitStoreI32(tablePtr, entryOffset, refNull, entryFuncIdOff);
        b.emitStoreI64(tablePtr, entryOffset, b.emitIconst64(0, 0), entryFuncPtrOff);
        b.emitJump(mergeBlock);

        // Non-null path
        b.switchToBlock(nonNullBlock);
        if (isExternRefTable(ctx, tableIdx)) {
            // ExternRef: store raw value directly, no function lookup
            b.emitStoreI32(tablePtr, entryOffset, b.emitIconst32(0), entryTypeOff);
            b.emitStoreI32(tablePtr, entryOffset, funcId, entryFuncIdOff);
            b.emitStoreI64(tablePtr, entryOffset, b.emitIconst64(0, 0), entryFuncPtrOff);
        } else {
            // FuncRef: resolve funcId → funcPtr+typeIdx
            int funcTablePtr =
                    b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.FUNC_TABLE_PTR);
            int funcPtrOffset = b.emitImul(funcId, b.emitIconst32(8));
            int funcPtr = b.emitLoadI64(funcTablePtr, funcPtrOffset, 0);
            int funcTypesPtr =
                    b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.FUNC_TYPES_PTR);
            int typeIdxOffset = b.emitImul(funcId, b.emitIconst32(4));
            int typeIdx = b.emitLoadI32(funcTypesPtr, typeIdxOffset, 0);
            b.emitStoreI32(tablePtr, entryOffset, typeIdx, entryTypeOff);
            b.emitStoreI32(tablePtr, entryOffset, funcId, entryFuncIdOff);
            b.emitStoreI64(tablePtr, entryOffset, funcPtr, entryFuncPtrOff);
        }
        b.emitJump(mergeBlock);

        b.switchToBlock(mergeBlock);
    }

    static void emitTableSize(EmitContext ctx, AnnotatedInstruction ins) {
        int tableIdx = (int) ins.operands()[0];
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);
        int tablePtr = loadTablePtr(ctx, tableIdx);
        int size = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);
        ctx.valueStack.push(size);
    }

    static void emitTableGrow(EmitContext ctx, AnnotatedInstruction ins) {
        int tableIdx = (int) ins.operands()[0];
        int delta = ctx.valueStack.pop();
        // Fill value is i64 (ref type on stack), narrow to i32 for table storage
        int fillValue = ctx.bridge.exports().emitIreduceI32(ctx.valueStack.pop());
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int tablePtr = loadTablePtr(ctx, tableIdx);
        int oldSize = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);
        int maxSize = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_MAX_OFFSET);

        // newSize = oldSize + delta
        int newSize = b.emitIadd(b.emitUextendI64(oldSize), b.emitUextendI64(delta));

        // if newSize > maxSize → return -1
        int overMax = b.emitIcmp(5, newSize, b.emitUextendI64(maxSize)); // UGT
        // if delta < 0 (unsigned: very large) → also fail
        // Combine: newSize > max OR newSize < oldSize (overflow)
        int overflow = b.emitIcmp(3, newSize, b.emitUextendI64(oldSize)); // ULT = overflow
        int fail = b.emitBor(overMax, overflow);

        int failBlock = b.createBlock();
        int okBlock = b.createBlock();
        int mergeBlock = b.createBlock();
        int mergeParam = b.appendBlockParam(mergeBlock, 0 /* TYPE_I32 */);

        b.emitBrif(fail, failBlock, okBlock);

        // Fail block: push -1
        b.switchToBlock(failBlock);
        b.emitJumpWithArg(mergeBlock, b.emitIconst32(-1));

        // OK block: update size, fill new slots via trampoline, return oldSize
        b.switchToBlock(okBlock);
        int newSizeI32 = b.emitIreduceI32(newSize);
        b.emitStoreI32(tablePtr, zero, newSizeI32, CtxBuffer.TABLE_SIZE_OFFSET);

        // Fill new slots via trampoline (runtime loop)
        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(oldSize), CtxBuffer.argOffset(0));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(newSizeI32), CtxBuffer.argOffset(1));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(fillValue), CtxBuffer.argOffset(2));
        b.emitStoreI64(argsPtr, zero, tablePtr, CtxBuffer.argOffset(3)); // already i64
        b.emitStoreI64(
                argsPtr, zero, b.emitUextendI64(b.emitIconst32(tableIdx)), CtxBuffer.argOffset(4));

        int tableOpsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar),
                zero,
                b.emitIconst32(-1), // sentinel for table grow fill
                CtxBuffer.ARG_COUNT);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        b.emitCallIndirect(trampolineSig, tableOpsPtr);

        b.emitJumpWithArg(mergeBlock, oldSize);

        b.switchToBlock(mergeBlock);
        ctx.valueStack.push(mergeParam);
    }

    static void emitTableFill(EmitContext ctx, AnnotatedInstruction ins) {
        int tableIdx = (int) ins.operands()[0];
        int size = ctx.valueStack.pop();
        // Fill value is i64 (ref type on stack), narrow to i32 for table storage
        int fillValue = ctx.bridge.exports().emitIreduceI32(ctx.valueStack.pop());
        int offset = ctx.valueStack.pop();
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int tablePtr = loadTablePtr(ctx, tableIdx);
        int tableSize = b.emitLoadI32(tablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);

        // Bounds check: offset + size > tableSize → trap
        int end = b.emitIadd(b.emitUextendI64(offset), b.emitUextendI64(size));
        int oob = b.emitIcmp(5, end, b.emitUextendI64(tableSize)); // UGT
        int trapBlock = b.createBlock();
        int okBlock = b.createBlock();
        b.emitBrif(oob, trapBlock, okBlock);
        fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_TABLE_OOB);
        b.switchToBlock(okBlock);

        // Emit fill via trampoline (runtime loop)
        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(offset), CtxBuffer.argOffset(0));
        int endI32 = b.emitIreduceI32(end);
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(endI32), CtxBuffer.argOffset(1));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(fillValue), CtxBuffer.argOffset(2));
        b.emitStoreI64(argsPtr, zero, tablePtr, CtxBuffer.argOffset(3)); // already i64
        b.emitStoreI64(
                argsPtr, zero, b.emitUextendI64(b.emitIconst32(tableIdx)), CtxBuffer.argOffset(4));

        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar),
                zero,
                b.emitIconst32(-2), // sentinel for table fill
                CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        b.emitCallIndirect(trampolineSig, trampolinePtr);
    }

    static void emitTableCopy(EmitContext ctx, AnnotatedInstruction ins) {
        int dstTableIdx = (int) ins.operands()[0];
        int srcTableIdx = (int) ins.operands()[1];
        int size = ctx.valueStack.pop();
        int srcOffset = ctx.valueStack.pop();
        int dstOffset = ctx.valueStack.pop();
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int srcTablePtr = loadTablePtr(ctx, srcTableIdx);
        int dstTablePtr = loadTablePtr(ctx, dstTableIdx);
        int srcTableSize = b.emitLoadI32(srcTablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);
        int dstTableSize = b.emitLoadI32(dstTablePtr, zero, CtxBuffer.TABLE_SIZE_OFFSET);

        // Bounds checks
        int srcEnd = b.emitIadd(b.emitUextendI64(srcOffset), b.emitUextendI64(size));
        int dstEnd = b.emitIadd(b.emitUextendI64(dstOffset), b.emitUextendI64(size));
        int srcOob = b.emitIcmp(5, srcEnd, b.emitUextendI64(srcTableSize));
        int dstOob = b.emitIcmp(5, dstEnd, b.emitUextendI64(dstTableSize));
        int oob = b.emitBor(srcOob, dstOob);

        int trapBlock = b.createBlock();
        int okBlock = b.createBlock();
        b.emitBrif(oob, trapBlock, okBlock);
        fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_TABLE_OOB);
        b.switchToBlock(okBlock);

        // Copy via trampoline (handles overlapping correctly)
        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(argsPtr, zero, srcTablePtr, CtxBuffer.argOffset(0)); // already i64
        b.emitStoreI64(argsPtr, zero, dstTablePtr, CtxBuffer.argOffset(1)); // already i64
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(srcOffset), CtxBuffer.argOffset(2));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(dstOffset), CtxBuffer.argOffset(3));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(size), CtxBuffer.argOffset(4));

        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar),
                zero,
                b.emitIconst32(-3), // sentinel for table copy
                CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        b.emitCallIndirect(trampolineSig, trampolinePtr);
    }

    static void emitTableInit(EmitContext ctx, AnnotatedInstruction ins) {
        int elemIdx = (int) ins.operands()[0];
        int tableIdx = (int) ins.operands()[1];
        int size = ctx.valueStack.pop();
        int srcOffset = ctx.valueStack.pop();
        int dstOffset = ctx.valueStack.pop();
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        // TABLE.INIT always goes through trampoline (needs elem segment data)
        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(
                argsPtr, zero, b.emitUextendI64(b.emitIconst32(tableIdx)), CtxBuffer.argOffset(0));
        b.emitStoreI64(
                argsPtr, zero, b.emitUextendI64(b.emitIconst32(elemIdx)), CtxBuffer.argOffset(1));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(dstOffset), CtxBuffer.argOffset(2));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(srcOffset), CtxBuffer.argOffset(3));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(size), CtxBuffer.argOffset(4));

        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar),
                zero,
                b.emitIconst32(-4), // sentinel for table init
                CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        b.emitCallIndirect(trampolineSig, trampolinePtr);
    }

    static void emitElemDrop(EmitContext ctx, AnnotatedInstruction ins) {
        int elemIdx = (int) ins.operands()[0];
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        // ELEM.DROP always goes through trampoline
        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(
                argsPtr, zero, b.emitUextendI64(b.emitIconst32(elemIdx)), CtxBuffer.argOffset(0));

        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar),
                zero,
                b.emitIconst32(-5), // sentinel for elem drop
                CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        b.emitCallIndirect(trampolineSig, trampolinePtr);
    }

    // --- Bulk memory operations (inline native memmove/memset) ---

    /**
     * Emit OOB check for bulk memory ops: if addr + size > memPages * 65536, trap.
     * Both addr and size are i32 runtime values.
     */
    private static void emitBulkBoundsCheck(EmitContext ctx, int addr, int size) {
        var b = ctx.bridge.exports();
        int addr64 = b.emitUextendI64(addr);
        int size64 = b.emitUextendI64(size);
        int end = b.emitIadd(addr64, size64);

        int zero = b.emitIconst32(0);
        int memPages = b.emitLoadI32(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.MEMORY_PAGES);
        int memPages64 = b.emitUextendI64(memPages);
        int memSize = b.emitIshl(memPages64, b.emitIconst64(16, 0));

        int oob = b.emitIcmp(5, end, memSize); // GT unsigned
        int trapBlock = b.createBlock();
        int okBlock = b.createBlock();
        b.emitBrif(oob, trapBlock, okBlock);

        fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_OOB);
        b.switchToBlock(okBlock);
    }

    static void emitMemoryCopy(EmitContext ctx) {
        int size = ctx.valueStack.pop();
        int srcOffset = ctx.valueStack.pop();
        int dstOffset = ctx.valueStack.pop();
        var b = ctx.bridge.exports();

        // OOB checks for both src and dst ranges
        emitBulkBoundsCheck(ctx, srcOffset, size);
        emitBulkBoundsCheck(ctx, dstOffset, size);

        // Compute native addresses: memBase + offset
        int memBase = b.useVar(ctx.memBaseVar);
        int effectiveDst = b.emitIadd(memBase, b.emitUextendI64(dstOffset));
        int effectiveSrc = b.emitIadd(memBase, b.emitUextendI64(srcOffset));
        int size64 = b.emitUextendI64(size);

        // Call memmove(dst, src, size) via function pointer in ctxBuffer
        int zero = b.emitIconst32(0);
        int memmovePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.MEMMOVE_PTR);
        int sigRef = ctx.getOrCreateMemopSigRef();
        b.pushCallArg(effectiveDst);
        b.pushCallArg(effectiveSrc);
        b.pushCallArg(size64);
        b.emitCallIndirect(sigRef, memmovePtr);
    }

    static void emitMemoryFill(EmitContext ctx) {
        int size = ctx.valueStack.pop();
        int value = ctx.valueStack.pop();
        int dstOffset = ctx.valueStack.pop();
        var b = ctx.bridge.exports();

        // OOB check for dst range
        emitBulkBoundsCheck(ctx, dstOffset, size);

        // Compute native address: memBase + dstOffset
        int memBase = b.useVar(ctx.memBaseVar);
        int effectiveDst = b.emitIadd(memBase, b.emitUextendI64(dstOffset));
        int value64 = b.emitUextendI64(value);
        int size64 = b.emitUextendI64(size);

        // Call memset(dst, value, size) via function pointer in ctxBuffer
        int zero = b.emitIconst32(0);
        int memsetPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.MEMSET_PTR);
        int sigRef = ctx.getOrCreateMemopSigRef();
        b.pushCallArg(effectiveDst);
        b.pushCallArg(value64);
        b.pushCallArg(size64);
        b.emitCallIndirect(sigRef, memsetPtr);
    }

    static void emitMemoryInit(EmitContext ctx, AnnotatedInstruction ins) {
        int segmentId = (int) ins.operands()[0];
        int size = ctx.valueStack.pop();
        int srcOffset = ctx.valueStack.pop();
        int dstOffset = ctx.valueStack.pop();
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(
                argsPtr, zero, b.emitUextendI64(b.emitIconst32(segmentId)), CtxBuffer.argOffset(0));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(dstOffset), CtxBuffer.argOffset(1));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(srcOffset), CtxBuffer.argOffset(2));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(size), CtxBuffer.argOffset(3));

        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar),
                zero,
                b.emitIconst32(-8), // sentinel for memory.init
                CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        b.emitCallIndirect(trampolineSig, trampolinePtr);
    }

    static void emitDataDrop(EmitContext ctx, AnnotatedInstruction ins) {
        int segmentId = (int) ins.operands()[0];
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(
                argsPtr, zero, b.emitUextendI64(b.emitIconst32(segmentId)), CtxBuffer.argOffset(0));

        b.emitStoreI32(
                b.useVar(ctx.ctxPtrVar),
                zero,
                b.emitIconst32(-9), // sentinel for data.drop
                CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        b.emitCallIndirect(trampolineSig, trampolinePtr);
    }

    // --- Atomic operations ---

    private static final int[] ATOMIC_LOAD_ACCESS_SIZE = {4, 8, 1, 2, 1, 2, 4};
    private static final int[] ATOMIC_STORE_ACCESS_SIZE = {4, 8, 1, 2, 1, 2, 4};
    private static final int[] ATOMIC_RMW_ACCESS_SIZE = {4, 8, 1, 2, 1, 2, 4};

    private static void emitAlignmentCheck(EmitContext ctx, int addr, int offset, int alignment) {
        if (alignment <= 1) {
            return; // no alignment needed for byte ops
        }
        var b = ctx.bridge.exports();
        // effective = (addr + offset) as i64
        int addr64 = b.emitUextendI64(addr);
        int effective =
                b.emitIadd(
                        addr64,
                        b.emitIconst64(
                                (int) (Integer.toUnsignedLong(offset) & 0xFFFFFFFFL),
                                (int) (Integer.toUnsignedLong(offset) >>> 32)));
        int mask = b.emitIconst64(alignment - 1, 0);
        int rem = b.emitBand(effective, mask);
        int zero64 = b.emitIconst64(0, 0);
        int misaligned = b.emitIcmp(1, rem, zero64); // NE
        int trapBlock = b.createBlock();
        int okBlock = b.createBlock();
        b.emitBrif(misaligned, trapBlock, okBlock);
        fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_UNALIGNED_ATOMIC);
        b.switchToBlock(okBlock);
    }

    static void emitAtomicLoad(EmitContext ctx, AnnotatedInstruction ins, int loadType) {
        int addr = ctx.valueStack.pop();
        int offset = (int) ins.operands()[1];
        int accessSize = ATOMIC_LOAD_ACCESS_SIZE[loadType];
        emitAlignmentCheck(ctx, addr, offset, accessSize);
        emitBoundsCheck(ctx, addr, offset, accessSize);
        int memBase = ctx.bridge.exports().useVar(ctx.memBaseVar);
        int result;
        switch (loadType) {
            case 0:
                result = ctx.bridge.exports().emitAtomicLoadI32(memBase, addr, offset);
                break;
            case 1:
                result = ctx.bridge.exports().emitAtomicLoadI64(memBase, addr, offset);
                break;
            case 2:
                result = ctx.bridge.exports().emitAtomicLoad8UI32(memBase, addr, offset);
                break;
            case 3:
                result = ctx.bridge.exports().emitAtomicLoad16UI32(memBase, addr, offset);
                break;
            case 4:
                result = ctx.bridge.exports().emitAtomicLoad8UI64(memBase, addr, offset);
                break;
            case 5:
                result = ctx.bridge.exports().emitAtomicLoad16UI64(memBase, addr, offset);
                break;
            case 6:
                result = ctx.bridge.exports().emitAtomicLoad32UI64(memBase, addr, offset);
                break;
            default:
                throw new IllegalArgumentException("Unknown atomic load type: " + loadType);
        }
        ctx.valueStack.push(result);
    }

    static void emitAtomicStore(EmitContext ctx, AnnotatedInstruction ins, int storeType) {
        int value = ctx.valueStack.pop();
        int addr = ctx.valueStack.pop();
        int offset = (int) ins.operands()[1];
        int accessSize = ATOMIC_STORE_ACCESS_SIZE[storeType];
        emitAlignmentCheck(ctx, addr, offset, accessSize);
        emitBoundsCheck(ctx, addr, offset, accessSize);
        int memBase = ctx.bridge.exports().useVar(ctx.memBaseVar);
        switch (storeType) {
            case 0:
                ctx.bridge.exports().emitAtomicStoreI32(memBase, addr, value, offset);
                break;
            case 1:
                ctx.bridge.exports().emitAtomicStoreI64(memBase, addr, value, offset);
                break;
            case 2:
                ctx.bridge.exports().emitAtomicStore8(memBase, addr, value, offset);
                break;
            case 3:
                ctx.bridge.exports().emitAtomicStore16(memBase, addr, value, offset);
                break;
            case 4:
                ctx.bridge.exports().emitAtomicStore8I64(memBase, addr, value, offset);
                break;
            case 5:
                ctx.bridge.exports().emitAtomicStore16I64(memBase, addr, value, offset);
                break;
            case 6:
                ctx.bridge.exports().emitAtomicStore32I64(memBase, addr, value, offset);
                break;
            default:
                throw new IllegalArgumentException("Unknown atomic store type: " + storeType);
        }
    }

    // rmwOp: 0=add, 1=sub, 2=and, 3=or, 4=xor, 5=xchg
    static void emitAtomicRmw(EmitContext ctx, AnnotatedInstruction ins, int widthType, int rmwOp) {
        int value = ctx.valueStack.pop();
        int addr = ctx.valueStack.pop();
        int offset = (int) ins.operands()[1];
        int accessSize = ATOMIC_RMW_ACCESS_SIZE[widthType];
        emitAlignmentCheck(ctx, addr, offset, accessSize);
        emitBoundsCheck(ctx, addr, offset, accessSize);
        int memBase = ctx.bridge.exports().useVar(ctx.memBaseVar);
        int result;
        switch (widthType) {
            case 0:
                result = ctx.bridge.exports().emitAtomicRmwI32(rmwOp, memBase, addr, value, offset);
                break;
            case 1:
                result = ctx.bridge.exports().emitAtomicRmwI64(rmwOp, memBase, addr, value, offset);
                break;
            case 2:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicRmw8UI32(rmwOp, memBase, addr, value, offset);
                break;
            case 3:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicRmw16UI32(rmwOp, memBase, addr, value, offset);
                break;
            case 4:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicRmw8UI64(rmwOp, memBase, addr, value, offset);
                break;
            case 5:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicRmw16UI64(rmwOp, memBase, addr, value, offset);
                break;
            case 6:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicRmw32UI64(rmwOp, memBase, addr, value, offset);
                break;
            default:
                throw new IllegalArgumentException("Unknown atomic RMW width: " + widthType);
        }
        ctx.valueStack.push(result);
    }

    static void emitAtomicCmpxchg(EmitContext ctx, AnnotatedInstruction ins, int widthType) {
        int replacement = ctx.valueStack.pop();
        int expected = ctx.valueStack.pop();
        int addr = ctx.valueStack.pop();
        int offset = (int) ins.operands()[1];
        int accessSize = ATOMIC_RMW_ACCESS_SIZE[widthType];
        emitAlignmentCheck(ctx, addr, offset, accessSize);
        emitBoundsCheck(ctx, addr, offset, accessSize);
        int memBase = ctx.bridge.exports().useVar(ctx.memBaseVar);
        int result;
        switch (widthType) {
            case 0:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicCasI32(memBase, addr, expected, replacement, offset);
                break;
            case 1:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicCasI64(memBase, addr, expected, replacement, offset);
                break;
            case 2:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicCas8UI32(memBase, addr, expected, replacement, offset);
                break;
            case 3:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicCas16UI32(memBase, addr, expected, replacement, offset);
                break;
            case 4:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicCas8UI64(memBase, addr, expected, replacement, offset);
                break;
            case 5:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicCas16UI64(memBase, addr, expected, replacement, offset);
                break;
            case 6:
                result =
                        ctx.bridge
                                .exports()
                                .emitAtomicCas32UI64(memBase, addr, expected, replacement, offset);
                break;
            default:
                throw new IllegalArgumentException("Unknown atomic CAS width: " + widthType);
        }
        ctx.valueStack.push(result);
    }

    static void emitAtomicWait32(EmitContext ctx, AnnotatedInstruction ins) {
        int timeout = ctx.valueStack.pop(); // i64
        int expected = ctx.valueStack.pop(); // i32
        int addr = ctx.valueStack.pop(); // i32
        int offset = (int) ins.operands()[1];
        emitAlignmentCheck(ctx, addr, offset, 4);
        emitBoundsCheck(ctx, addr, offset, 4);
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(addr), CtxBuffer.argOffset(0));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(expected), CtxBuffer.argOffset(1));
        b.emitStoreI64(
                argsPtr,
                zero,
                b.emitIconst64(
                        (int) (Integer.toUnsignedLong(offset) & 0xFFFFFFFFL),
                        (int) (Integer.toUnsignedLong(offset) >>> 32)),
                CtxBuffer.argOffset(2));
        b.emitStoreI64(argsPtr, zero, timeout, CtxBuffer.argOffset(3));

        b.emitStoreI32(b.useVar(ctx.ctxPtrVar), zero, b.emitIconst32(-10), CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        int rawResult = b.emitCallIndirect(trampolineSig, trampolinePtr);
        ctx.valueStack.push(b.emitIreduceI32(rawResult));
    }

    static void emitAtomicWait64(EmitContext ctx, AnnotatedInstruction ins) {
        int timeout = ctx.valueStack.pop(); // i64
        int expected = ctx.valueStack.pop(); // i64
        int addr = ctx.valueStack.pop(); // i32
        int offset = (int) ins.operands()[1];
        emitAlignmentCheck(ctx, addr, offset, 8);
        emitBoundsCheck(ctx, addr, offset, 8);
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(addr), CtxBuffer.argOffset(0));
        b.emitStoreI64(argsPtr, zero, expected, CtxBuffer.argOffset(1));
        b.emitStoreI64(
                argsPtr,
                zero,
                b.emitIconst64(
                        (int) (Integer.toUnsignedLong(offset) & 0xFFFFFFFFL),
                        (int) (Integer.toUnsignedLong(offset) >>> 32)),
                CtxBuffer.argOffset(2));
        b.emitStoreI64(argsPtr, zero, timeout, CtxBuffer.argOffset(3));

        b.emitStoreI32(b.useVar(ctx.ctxPtrVar), zero, b.emitIconst32(-11), CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        int rawResult = b.emitCallIndirect(trampolineSig, trampolinePtr);
        ctx.valueStack.push(b.emitIreduceI32(rawResult));
    }

    static void emitAtomicNotify(EmitContext ctx, AnnotatedInstruction ins) {
        int count = ctx.valueStack.pop(); // i32
        int addr = ctx.valueStack.pop(); // i32
        int offset = (int) ins.operands()[1];
        emitAlignmentCheck(ctx, addr, offset, 4);
        emitBoundsCheck(ctx, addr, offset, 4);
        var b = ctx.bridge.exports();
        int zero = b.emitIconst32(0);

        int argsPtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(addr), CtxBuffer.argOffset(0));
        b.emitStoreI64(argsPtr, zero, b.emitUextendI64(count), CtxBuffer.argOffset(1));
        b.emitStoreI64(
                argsPtr,
                zero,
                b.emitIconst64(
                        (int) (Integer.toUnsignedLong(offset) & 0xFFFFFFFFL),
                        (int) (Integer.toUnsignedLong(offset) >>> 32)),
                CtxBuffer.argOffset(2));

        b.emitStoreI32(b.useVar(ctx.ctxPtrVar), zero, b.emitIconst32(-12), CtxBuffer.ARG_COUNT);
        int trampolinePtr = b.emitLoadI64(b.useVar(ctx.ctxPtrVar), zero, CtxBuffer.TRAMPOLINE_PTR);
        int trampolineSig = ctx.getOrCreateTrampolineSigRef();
        b.pushCallArg(b.useVar(ctx.ctxPtrVar));
        int rawResult = b.emitCallIndirect(trampolineSig, trampolinePtr);
        ctx.valueStack.push(b.emitIreduceI32(rawResult));
    }

    static void emitAtomicFence(EmitContext ctx) {
        ctx.bridge.exports().emitFence();
    }

    // --- Reference type operations ---

    static void emitRefNull(EmitContext ctx) {
        // Ref types are i64 on the value stack. REF_NULL_VALUE = -1.
        // Sign-extend so -1 as i32 → -1 as i64 (matching Java's (long)(int)-1).
        var b = ctx.bridge.exports();
        ctx.valueStack.push(b.emitSextendI64(b.emitIconst32(-1)));
    }

    static void emitRefIsNull(EmitContext ctx) {
        var b = ctx.bridge.exports();
        int val = ctx.valueStack.pop();
        // val is i64 (ref type), compare against REF_NULL_VALUE (-1 as i64)
        int refNull = b.emitSextendI64(b.emitIconst32(-1));
        int isNull = b.emitIcmp(0, val, refNull); // EQ, both i64
        ctx.valueStack.push(isNull);
    }

    static void emitRefFunc(EmitContext ctx, AnnotatedInstruction ins) {
        int funcIdx = (int) ins.operands()[0];
        // Ref types are i64 on the value stack. FuncIds are non-negative,
        // so sign-extend and uextend give the same result.
        var b = ctx.bridge.exports();
        ctx.valueStack.push(b.emitSextendI64(b.emitIconst32(funcIdx)));
    }
}
