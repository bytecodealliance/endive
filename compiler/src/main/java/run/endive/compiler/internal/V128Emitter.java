package run.endive.compiler.internal;

import static org.objectweb.asm.Type.INT_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;
import static run.endive.compiler.internal.CompilerUtil.emitInvokeStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.InstructionAdapter;
import run.endive.runtime.OpCodeIdentifier;
import run.endive.runtime.internal.V128Words;
import run.endive.wasm.types.OpCode;

/** Emits v128 ops on two longs, low word first: inline, or one V128Words call per word. */
final class V128Emitter {

    // spilled v128 operand i at t + 4 * i (word w at + 2 * w), a scalar at t + 12, an address at 14
    static final int TEMP_SLOTS = 15;

    // one JVM instruction per word: as calls they would use up C2's inlining budget
    private static final Set<OpCode> INLINE =
            EnumSet.of(
                    OpCode.V128_AND,
                    OpCode.V128_OR,
                    OpCode.V128_XOR,
                    OpCode.V128_ANDNOT,
                    OpCode.V128_NOT,
                    OpCode.I64x2_ADD,
                    OpCode.I64x2_SUB,
                    OpCode.I64x2_MUL,
                    OpCode.I64x2_NEG,
                    OpCode.I64x2_SHL,
                    OpCode.I64x2_SHR_S,
                    OpCode.I64x2_SHR_U,
                    OpCode.I64x2_SPLAT,
                    OpCode.I64x2_EXTRACT_LANE,
                    OpCode.I64x2_REPLACE_LANE);

    private static final Set<SimdOp.Kind> NO_HELPER =
            EnumSet.of(
                    SimdOp.Kind.CONST,
                    SimdOp.Kind.LOAD,
                    SimdOp.Kind.LOAD_LANE,
                    SimdOp.Kind.STORE,
                    SimdOp.Kind.STORE_LANE,
                    SimdOp.Kind.SHUFFLE);

    private static final Map<OpCode, Method> HELPERS = new EnumMap<>(OpCode.class);

    static {
        for (var m : V128Words.class.getMethods()) {
            var id = m.getAnnotation(OpCodeIdentifier.class);
            if (id != null && HELPERS.put(id.value(), m) != null) {
                throw new IllegalStateException("two V128Words helpers for " + id.value());
            }
        }
        var missing = new ArrayList<OpCode>();
        for (var simd : SimdOp.all()) {
            boolean needsHelper = !NO_HELPER.contains(simd.kind) && !INLINE.contains(simd.op);
            if (needsHelper != HELPERS.containsKey(simd.op)) {
                missing.add(simd.op);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("V128Words helpers missing or unused: " + missing);
        }
    }

    private final Context ctx;
    private final CompilerInstruction ins;
    private final InstructionAdapter asm;
    private final int t;

    private V128Emitter(Context ctx, CompilerInstruction ins, InstructionAdapter asm) {
        this.ctx = ctx;
        this.ins = ins;
        this.asm = asm;
        this.t = ctx.tempSlot();
    }

    static void emit(Context ctx, CompilerInstruction ins, InstructionAdapter asm) {
        new V128Emitter(ctx, ins, asm).emitOp();
    }

    // [] -> [lo, hi] of a local
    static void load(InstructionAdapter asm, int slot) {
        asm.load(slot, LONG_TYPE);
        asm.load(slot + 2, LONG_TYPE);
    }

    // [lo, hi] -> [], into a local
    static void store(InstructionAdapter asm, int slot) {
        asm.store(slot + 2, LONG_TYPE);
        asm.store(slot, LONG_TYPE);
    }

    static void zero(InstructionAdapter asm) {
        asm.lconst(0);
        asm.lconst(0);
    }

    private int word(int vector, int w) {
        return t + 4 * vector + 2 * w;
    }

    private int scalar() {
        return t + 12;
    }

    private int address() {
        return t + 14;
    }

    // [v0 ... vn-1] -> []
    private void spill(int count) {
        for (int i = count - 1; i >= 0; i--) {
            store(asm, t + 4 * i);
        }
    }

    private void lload(int slot) {
        asm.load(slot, LONG_TYPE);
    }

    private void call(OpCode op) {
        emitInvokeStatic(asm, HELPERS.get(op));
    }

    private void emitOp() {
        var simd = SimdOp.of(ins);
        if (INLINE.contains(simd.op)) {
            emitWordOp(simd.op);
            return;
        }
        switch (simd.kind) {
            case CONST:
                asm.lconst(ins.operand(0));
                asm.lconst(ins.operand(1));
                return;
            case LOAD:
                load(simd.op);
                return;
            case LOAD_LANE:
                loadLane(simd.op);
                return;
            case STORE:
                storeVector();
                return;
            case STORE_LANE:
                storeLane(simd.op);
                return;
            case SHUFFLE:
                shuffle(ins.operand(0), ins.operand(1));
                return;
            case SPLAT:
                call(simd.op);
                asm.dup2();
                return;
            case EXTRACT:
                {
                    int lane = (int) ins.operand(0);
                    spill(1);
                    lload(word(0, lane / simd.lanesPerWord));
                    asm.iconst(lane % simd.lanesPerWord);
                    call(simd.op);
                    return;
                }
            case REPLACE:
                {
                    int lane = (int) ins.operand(0);
                    var type = CompilerUtil.asmType(simd.scalar);
                    asm.store(scalar(), type);
                    spill(1);
                    for (int w = 0; w < 2; w++) {
                        lload(word(0, w));
                        if (w == lane / simd.lanesPerWord) {
                            asm.iconst(lane % simd.lanesPerWord);
                            asm.load(scalar(), type);
                            call(simd.op);
                        }
                    }
                    return;
                }
            case SHIFT:
                asm.store(scalar(), INT_TYPE);
                spill(1);
                for (int w = 0; w < 2; w++) {
                    lload(word(0, w));
                    asm.load(scalar(), INT_TYPE);
                    call(simd.op);
                }
                return;
            case TEST:
                call(simd.op);
                return;
            case UNARY_ZERO:
                call(simd.op);
                asm.lconst(0);
                return;
            case NARROW:
                spill(2);
                for (int i = 0; i < 2; i++) {
                    lload(word(i, 0));
                    lload(word(i, 1));
                    call(simd.op);
                }
                return;
            case SWIZZLE:
                spill(2);
                for (int w = 0; w < 2; w++) {
                    lload(word(0, 0));
                    lload(word(0, 1));
                    lload(word(1, w));
                    call(simd.op);
                }
                return;
            case UNARY_HALF:
            case BINARY_HALF:
                {
                    int vectors = simd.kind == SimdOp.Kind.UNARY_HALF ? 1 : 2;
                    spill(vectors);
                    for (int w = 0; w < 2; w++) {
                        for (int i = 0; i < vectors; i++) {
                            lload(word(i, simd.half));
                        }
                        asm.iconst(w);
                        call(simd.op);
                    }
                    return;
                }
            case UNARY:
            case BINARY:
            case TERNARY:
                {
                    int vectors = simd.inputs().size();
                    spill(vectors);
                    for (int w = 0; w < 2; w++) {
                        for (int i = 0; i < vectors; i++) {
                            lload(word(i, w));
                        }
                        call(simd.op);
                    }
                    return;
                }
            default:
                throw new IllegalStateException("no emitter for " + simd.kind);
        }
    }

    private void emitWordOp(OpCode op) {
        switch (op) {
            case V128_AND:
                binaryWord(Opcodes.LAND);
                return;
            case V128_OR:
                binaryWord(Opcodes.LOR);
                return;
            case V128_XOR:
                binaryWord(Opcodes.LXOR);
                return;
            case I64x2_ADD:
                binaryWord(Opcodes.LADD);
                return;
            case I64x2_SUB:
                binaryWord(Opcodes.LSUB);
                return;
            case I64x2_MUL:
                binaryWord(Opcodes.LMUL);
                return;
            case V128_ANDNOT:
                spill(2);
                for (int w = 0; w < 2; w++) {
                    lload(word(0, w));
                    lload(word(1, w));
                    asm.lconst(-1);
                    asm.xor(LONG_TYPE);
                    asm.and(LONG_TYPE);
                }
                return;
            case V128_NOT:
                spill(1);
                for (int w = 0; w < 2; w++) {
                    lload(word(0, w));
                    asm.lconst(-1);
                    asm.xor(LONG_TYPE);
                }
                return;
            case I64x2_NEG:
                spill(1);
                for (int w = 0; w < 2; w++) {
                    lload(word(0, w));
                    asm.neg(LONG_TYPE);
                }
                return;
            case I64x2_SHL:
                shiftWord(Opcodes.LSHL);
                return;
            case I64x2_SHR_S:
                shiftWord(Opcodes.LSHR);
                return;
            case I64x2_SHR_U:
                shiftWord(Opcodes.LUSHR);
                return;
            case I64x2_SPLAT:
                asm.dup2();
                return;
            case I64x2_EXTRACT_LANE:
                spill(1);
                lload(word(0, (int) ins.operand(0)));
                return;
            case I64x2_REPLACE_LANE:
                asm.store(scalar(), LONG_TYPE);
                spill(1);
                for (int w = 0; w < 2; w++) {
                    lload(w == ins.operand(0) ? scalar() : word(0, w));
                }
                return;
            default:
                throw new IllegalStateException("no inline emitter for " + op);
        }
    }

    // [a, b] -> [a op b], word by word
    private void binaryWord(int opcode) {
        spill(2);
        for (int w = 0; w < 2; w++) {
            lload(word(0, w));
            lload(word(1, w));
            asm.visitInsn(opcode);
        }
    }

    // [v, count] -> [v shifted], word by word; the JVM masks the count to 6 bits as wasm does
    private void shiftWord(int opcode) {
        asm.store(scalar(), INT_TYPE);
        spill(1);
        for (int w = 0; w < 2; w++) {
            lload(word(0, w));
            asm.load(scalar(), INT_TYPE);
            asm.visitInsn(opcode);
        }
    }

    // [a, b] -> [selected bytes]: a shift and mask per run of bytes, one rotate per rotated word
    private void shuffle(long lo, long hi) {
        spill(2);
        int[] lanes = new int[16];
        for (int i = 0; i < 16; i++) {
            lanes[i] = (int) ((i < 8 ? lo >>> (i * 8) : hi >>> ((i - 8) * 8)) & 0x1F);
        }
        for (int w = 0; w < 2; w++) {
            int source = lanes[w * 8] / 8;
            int rotation = lanes[w * 8] % 8;
            boolean rotated = true;
            for (int j = 0; j < 8; j++) {
                int lane = lanes[w * 8 + j];
                rotated &= lane / 8 == source && lane % 8 == (j + rotation) % 8;
            }
            if (rotated) {
                lload(sourceWord(source));
                if (rotation != 0) {
                    asm.iconst(rotation * 8);
                    asm.invokestatic("java/lang/Long", "rotateRight", "(JI)J", false);
                }
                continue;
            }
            boolean first = true;
            int j = 0;
            while (j < 8) {
                int lane = lanes[w * 8 + j];
                int len = 1;
                while (j + len < 8
                        && lanes[w * 8 + j + len] == lane + len
                        && (lane + len) / 8 == lane / 8) {
                    len++;
                }
                int shift = (j - lane % 8) * 8;
                lload(sourceWord(lane / 8));
                if (shift > 0) {
                    asm.iconst(shift);
                    asm.shl(LONG_TYPE);
                } else if (shift < 0) {
                    asm.iconst(-shift);
                    asm.ushr(LONG_TYPE);
                }
                if (len < 8) {
                    asm.lconst(((1L << (len * 8)) - 1) << (j * 8));
                    asm.and(LONG_TYPE);
                }
                if (!first) {
                    asm.or(LONG_TYPE);
                }
                first = false;
                j += len;
            }
        }
    }

    // source word 0 to 3 of a shuffle: a low, a high, b low, b high
    private int sourceWord(int source) {
        return word(source / 2, source % 2);
    }

    // the static offset, or a throw if base + offset + bytes is out of bounds for any base
    private int offset(int bytes) {
        long offset = ins.operand(1);
        if (offset < 0 || offset + bytes > Integer.MAX_VALUE) {
            emitInvokeStatic(asm, ShadedRefs.THROW_OUT_OF_BOUNDS_MEMORY_ACCESS);
            asm.athrow();
        }
        return (int) offset;
    }

    // [base] -> [value read at base + offset + delta]
    private void read(Method method, int offset, int delta) {
        asm.iconst(offset + delta);
        Emitters.emitMemoryForIndex(ctx, ins, asm, 2);
        emitInvokeStatic(asm, method);
    }

    // [base] -> [v]
    private void load(OpCode op) {
        switch (op) {
            case V128_LOAD:
                {
                    int offset = offset(16);
                    asm.store(address(), INT_TYPE);
                    for (int w = 0; w < 2; w++) {
                        asm.load(address(), INT_TYPE);
                        read(ShadedRefs.MEMORY_READ_LONG, offset, w * 8);
                    }
                    return;
                }
            case V128_LOAD8x8_S:
                loadExtend(OpCode.I16x8_EXTEND_LOW_I8x16_S);
                return;
            case V128_LOAD8x8_U:
                loadExtend(OpCode.I16x8_EXTEND_LOW_I8x16_U);
                return;
            case V128_LOAD16x4_S:
                loadExtend(OpCode.I32x4_EXTEND_LOW_I16x8_S);
                return;
            case V128_LOAD16x4_U:
                loadExtend(OpCode.I32x4_EXTEND_LOW_I16x8_U);
                return;
            case V128_LOAD32x2_S:
                loadExtend(OpCode.I64x2_EXTEND_LOW_I32x4_S);
                return;
            case V128_LOAD32x2_U:
                loadExtend(OpCode.I64x2_EXTEND_LOW_I32x4_U);
                return;
            case V128_LOAD8_SPLAT:
                read(ShadedRefs.MEMORY_READ_BYTE, offset(1), 0);
                call(OpCode.I8x16_SPLAT);
                asm.dup2();
                return;
            case V128_LOAD16_SPLAT:
                read(ShadedRefs.MEMORY_READ_SHORT, offset(2), 0);
                call(OpCode.I16x8_SPLAT);
                asm.dup2();
                return;
            case V128_LOAD32_SPLAT:
                read(ShadedRefs.MEMORY_READ_INT, offset(4), 0);
                call(OpCode.I32x4_SPLAT);
                asm.dup2();
                return;
            case V128_LOAD64_SPLAT:
                read(ShadedRefs.MEMORY_READ_LONG, offset(8), 0);
                asm.dup2();
                return;
            case V128_LOAD32_ZERO:
                read(ShadedRefs.MEMORY_READ_INT, offset(4), 0);
                asm.visitInsn(Opcodes.I2L);
                asm.lconst(0xFFFFFFFFL);
                asm.and(LONG_TYPE);
                asm.lconst(0);
                return;
            case V128_LOAD64_ZERO:
                read(ShadedRefs.MEMORY_READ_LONG, offset(8), 0);
                asm.lconst(0);
                return;
            default:
                throw new IllegalStateException("no emitter for v128 load " + op);
        }
    }

    // [base] -> [the 8 bytes at base + offset widened by the extend op]
    private void loadExtend(OpCode extend) {
        read(ShadedRefs.MEMORY_READ_LONG, offset(8), 0);
        asm.store(scalar(), LONG_TYPE);
        for (int w = 0; w < 2; w++) {
            lload(scalar());
            asm.iconst(w);
            call(extend);
        }
    }

    // [base, v] -> [v with one lane read from memory]
    private void loadLane(OpCode op) {
        int lane = (int) ins.operand(3);
        spill(1);
        if (op == OpCode.V128_LOAD64_LANE) {
            read(ShadedRefs.MEMORY_READ_LONG, offset(8), 0);
            asm.store(scalar(), LONG_TYPE);
            for (int w = 0; w < 2; w++) {
                lload(w == lane ? scalar() : word(0, w));
            }
            return;
        }
        Method read;
        OpCode replace;
        int perWord;
        int bytes;
        switch (op) {
            case V128_LOAD8_LANE:
                read = ShadedRefs.MEMORY_READ_BYTE;
                replace = OpCode.I8x16_REPLACE_LANE;
                perWord = 8;
                bytes = 1;
                break;
            case V128_LOAD16_LANE:
                read = ShadedRefs.MEMORY_READ_SHORT;
                replace = OpCode.I16x8_REPLACE_LANE;
                perWord = 4;
                bytes = 2;
                break;
            case V128_LOAD32_LANE:
                read = ShadedRefs.MEMORY_READ_INT;
                replace = OpCode.I32x4_REPLACE_LANE;
                perWord = 2;
                bytes = 4;
                break;
            default:
                throw new IllegalStateException("no emitter for v128 lane load " + op);
        }
        read(read, offset(bytes), 0);
        asm.store(scalar(), INT_TYPE);
        for (int w = 0; w < 2; w++) {
            lload(word(0, w));
            if (w == lane / perWord) {
                asm.iconst(lane % perWord);
                asm.load(scalar(), INT_TYPE);
                call(replace);
            }
        }
    }

    // [base, v] -> [], the high word first: if it is out of bounds, nothing gets written
    private void storeVector() {
        int offset = offset(16);
        spill(1);
        asm.store(address(), INT_TYPE);
        for (int w = 1; w >= 0; w--) {
            asm.load(address(), INT_TYPE);
            lload(word(0, w));
            asm.iconst(offset + w * 8);
            Emitters.emitMemoryForIndex(ctx, ins, asm, 2);
            emitInvokeStatic(asm, ShadedRefs.MEMORY_WRITE_LONG);
        }
    }

    // [base, v] -> [], one lane written to memory
    private void storeLane(OpCode op) {
        int lane = (int) ins.operand(3);
        Method write;
        OpCode extract;
        int perWord;
        int bytes;
        switch (op) {
            case V128_STORE8_LANE:
                write = ShadedRefs.MEMORY_WRITE_BYTE;
                extract = OpCode.I8x16_EXTRACT_LANE_U;
                perWord = 8;
                bytes = 1;
                break;
            case V128_STORE16_LANE:
                write = ShadedRefs.MEMORY_WRITE_SHORT;
                extract = OpCode.I16x8_EXTRACT_LANE_U;
                perWord = 4;
                bytes = 2;
                break;
            case V128_STORE32_LANE:
                write = ShadedRefs.MEMORY_WRITE_INT;
                extract = OpCode.I32x4_EXTRACT_LANE;
                perWord = 2;
                bytes = 4;
                break;
            case V128_STORE64_LANE:
                write = ShadedRefs.MEMORY_WRITE_LONG;
                extract = null;
                perWord = 1;
                bytes = 8;
                break;
            default:
                throw new IllegalStateException("no emitter for v128 lane store " + op);
        }
        int offset = offset(bytes);
        spill(1);
        lload(word(0, lane / perWord));
        if (extract != null) {
            asm.iconst(lane % perWord);
            call(extract);
            if (bytes == 1) {
                asm.visitInsn(Opcodes.I2B);
            } else if (bytes == 2) {
                asm.visitInsn(Opcodes.I2S);
            }
        }
        asm.iconst(offset);
        Emitters.emitMemoryForIndex(ctx, ins, asm, 2);
        emitInvokeStatic(asm, write);
    }
}
