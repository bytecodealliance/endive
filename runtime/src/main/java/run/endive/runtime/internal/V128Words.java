package run.endive.runtime.internal;

import run.endive.runtime.OpCodeIdentifier;
import run.endive.wasm.types.OpCode;

/** The v128 ops of compiled code, one 64-bit word of the result per call; lane 0 is lowest. */
public final class V128Words {
    private static final long H8 = 0x8080808080808080L;
    private static final long H16 = 0x8000800080008000L;
    private static final long H32 = 0x8000000080000000L;
    private static final long L8 = 0x0101010101010101L;
    private static final long L16 = 0x0001000100010001L;
    private static final long L32 = 0x0000000100000001L;
    private static final long M32 = 0xFFFFFFFFL;

    private V128Words() {}

    // SWAR on lanes whose high bits are h

    private static long add(long x, long y, long h) {
        return ((x & ~h) + (y & ~h)) ^ ((x ^ y) & h);
    }

    private static long sub(long x, long y, long h) {
        return ((x | h) - (y & ~h)) ^ ((x ^ ~y) & h);
    }

    // the high bit of some lanes, bits wide, to all ones in those lanes
    private static long expand(long high, int bits) {
        return (high >>> (bits - 1)) * (-1L >>> (64 - bits));
    }

    private static long nonzero(long x, long h) {
        return (((x & ~h) + ~h) | x) & h;
    }

    private static long eq(long x, long y, long h) {
        return nonzero(x ^ y, h) ^ h;
    }

    private static long ltu(long x, long y, long h) {
        return ((~x & y) | (~(x ^ y) & sub(x, y, h))) & h;
    }

    private static long lts(long x, long y, long h) {
        long d = sub(x, y, h);
        return (d ^ ((x ^ y) & (d ^ x))) & h;
    }

    private static long select(long mask, long x, long y) {
        return (x & mask) | (y & ~mask);
    }

    // r is a +/- b; MIN or MAX where the lanes overflowed
    private static long saturate(long a, long r, long overflow, long h, int bits) {
        long ov = expand(overflow & h, bits);
        return ((expand(a & h, bits) ^ ~h) & ov) | (r & ~ov);
    }

    private static long abs(long a, long h, int bits) {
        long s = (a & h) >>> (bits - 1);
        long x = a ^ (s * (-1L >>> (64 - bits)));
        return ((x & ~h) + s) ^ (x & h);
    }

    // arithmetic shift right by n < bits of each lane
    private static long shr(long a, int n, int bits) {
        long lane = -1L >>> (64 - bits);
        long ones = laneOnes(bits);
        long fill = (lane << (bits - n)) & lane;
        return ((a >>> n) & ((lane >>> n) * ones)) | (((a >>> (bits - 1)) & ones) * fill);
    }

    private static long laneOnes(int bits) {
        return bits == 8 ? L8 : bits == 16 ? L16 : L32;
    }

    private static long pack32(int lo, int hi) {
        return (lo & M32) | ((long) hi << 32);
    }

    private static int bool(boolean b) {
        return b ? -1 : 0;
    }

    private static long dbool(boolean b) {
        return b ? -1L : 0L;
    }

    private static float f32(long w, int lane) {
        return Float.intBitsToFloat((int) (w >>> (lane * 32)));
    }

    private static long bits(float lo, float hi) {
        return pack32(Float.floatToRawIntBits(lo), Float.floatToRawIntBits(hi));
    }

    private static double f64(long w) {
        return Double.longBitsToDouble(w);
    }

    private static long dbits(double d) {
        return Double.doubleToRawLongBits(d);
    }

    // v128

    @OpCodeIdentifier(OpCode.V128_BITSELECT)
    public static long V128_BITSELECT(long a, long b, long c) {
        return b ^ ((a ^ b) & c);
    }

    @OpCodeIdentifier(OpCode.V128_ANY_TRUE)
    public static int V128_ANY_TRUE(long lo, long hi) {
        return (lo | hi) != 0 ? 1 : 0;
    }

    // word w of the result from both words of a and word w of the indexes
    @OpCodeIdentifier(OpCode.I8x16_SWIZZLE)
    public static long I8x16_SWIZZLE(long a0, long a1, long idx) {
        long r = 0;
        for (int i = 0; i < 8; i++) {
            int j = (int) (idx >>> (i * 8)) & 0xFF;
            if (j < 16) {
                long word = j < 8 ? a0 : a1;
                r |= ((word >>> ((j & 7) * 8)) & 0xFF) << (i * 8);
            }
        }
        return r;
    }

    // i8x16

    @OpCodeIdentifier(OpCode.I8x16_SPLAT)
    public static long I8x16_SPLAT(int x) {
        return (x & 0xFFL) * L8;
    }

    @OpCodeIdentifier(OpCode.I8x16_EXTRACT_LANE_S)
    public static int I8x16_EXTRACT_LANE_S(long w, int lane) {
        return (byte) (w >>> (lane * 8));
    }

    @OpCodeIdentifier(OpCode.I8x16_EXTRACT_LANE_U)
    public static int I8x16_EXTRACT_LANE_U(long w, int lane) {
        return (int) (w >>> (lane * 8)) & 0xFF;
    }

    @OpCodeIdentifier(OpCode.I8x16_REPLACE_LANE)
    public static long I8x16_REPLACE_LANE(long w, int lane, int x) {
        int s = lane * 8;
        return (w & ~(0xFFL << s)) | ((x & 0xFFL) << s);
    }

    @OpCodeIdentifier(OpCode.I8x16_EQ)
    public static long I8x16_EQ(long a, long b) {
        return expand(eq(a, b, H8), 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_NE)
    public static long I8x16_NE(long a, long b) {
        return ~I8x16_EQ(a, b);
    }

    @OpCodeIdentifier(OpCode.I8x16_LT_S)
    public static long I8x16_LT_S(long a, long b) {
        return expand(lts(a, b, H8), 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_LT_U)
    public static long I8x16_LT_U(long a, long b) {
        return expand(ltu(a, b, H8), 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_GT_S)
    public static long I8x16_GT_S(long a, long b) {
        return I8x16_LT_S(b, a);
    }

    @OpCodeIdentifier(OpCode.I8x16_GT_U)
    public static long I8x16_GT_U(long a, long b) {
        return I8x16_LT_U(b, a);
    }

    @OpCodeIdentifier(OpCode.I8x16_LE_S)
    public static long I8x16_LE_S(long a, long b) {
        return ~I8x16_LT_S(b, a);
    }

    @OpCodeIdentifier(OpCode.I8x16_LE_U)
    public static long I8x16_LE_U(long a, long b) {
        return ~I8x16_LT_U(b, a);
    }

    @OpCodeIdentifier(OpCode.I8x16_GE_S)
    public static long I8x16_GE_S(long a, long b) {
        return ~I8x16_LT_S(a, b);
    }

    @OpCodeIdentifier(OpCode.I8x16_GE_U)
    public static long I8x16_GE_U(long a, long b) {
        return ~I8x16_LT_U(a, b);
    }

    @OpCodeIdentifier(OpCode.I8x16_ABS)
    public static long I8x16_ABS(long a) {
        return abs(a, H8, 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_NEG)
    public static long I8x16_NEG(long a) {
        return sub(0, a, H8);
    }

    @OpCodeIdentifier(OpCode.I8x16_POPCNT)
    public static long I8x16_POPCNT(long a) {
        long x = a - ((a >>> 1) & 0x5555555555555555L);
        x = (x & 0x3333333333333333L) + ((x >>> 2) & 0x3333333333333333L);
        return (x + (x >>> 4)) & 0x0F0F0F0F0F0F0F0FL;
    }

    private static int signBits8(long w) {
        return (int) ((((w >>> 7) & L8) * 0x0102040810204080L) >>> 56);
    }

    @OpCodeIdentifier(OpCode.I8x16_ALL_TRUE)
    public static int I8x16_ALL_TRUE(long lo, long hi) {
        return (nonzero(lo, H8) & nonzero(hi, H8)) == H8 ? 1 : 0;
    }

    @OpCodeIdentifier(OpCode.I8x16_BITMASK)
    public static int I8x16_BITMASK(long lo, long hi) {
        return signBits8(lo) | (signBits8(hi) << 8);
    }

    // word 0 of the result from the words of a, word 1 from the words of b
    @OpCodeIdentifier(OpCode.I8x16_NARROW_I16x8_S)
    public static long I8x16_NARROW_I16x8_S(long w0, long w1) {
        long r = 0;
        for (int i = 0; i < 8; i++) {
            int x = (short) ((i < 4 ? w0 : w1) >>> ((i & 3) * 16));
            r |= (Math.min(Math.max(x, Byte.MIN_VALUE), Byte.MAX_VALUE) & 0xFFL) << (i * 8);
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I8x16_NARROW_I16x8_U)
    public static long I8x16_NARROW_I16x8_U(long w0, long w1) {
        long r = 0;
        for (int i = 0; i < 8; i++) {
            int x = (short) ((i < 4 ? w0 : w1) >>> ((i & 3) * 16));
            r |= (long) Math.min(Math.max(x, 0), 0xFF) << (i * 8);
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I8x16_SHL)
    public static long I8x16_SHL(long a, int n) {
        n &= 7;
        return (a << n) & (((0xFFL << n) & 0xFF) * L8);
    }

    @OpCodeIdentifier(OpCode.I8x16_SHR_S)
    public static long I8x16_SHR_S(long a, int n) {
        return shr(a, n & 7, 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_SHR_U)
    public static long I8x16_SHR_U(long a, int n) {
        n &= 7;
        return (a >>> n) & ((0xFFL >>> n) * L8);
    }

    @OpCodeIdentifier(OpCode.I8x16_ADD)
    public static long I8x16_ADD(long a, long b) {
        return add(a, b, H8);
    }

    @OpCodeIdentifier(OpCode.I8x16_ADD_SAT_S)
    public static long I8x16_ADD_SAT_S(long a, long b) {
        long r = add(a, b, H8);
        return saturate(a, r, (a ^ r) & (b ^ r), H8, 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_ADD_SAT_U)
    public static long I8x16_ADD_SAT_U(long a, long b) {
        long s = add(a, b, H8);
        return s | expand(((a & b) | ((a | b) & ~s)) & H8, 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_SUB)
    public static long I8x16_SUB(long a, long b) {
        return sub(a, b, H8);
    }

    @OpCodeIdentifier(OpCode.I8x16_SUB_SAT_S)
    public static long I8x16_SUB_SAT_S(long a, long b) {
        long r = sub(a, b, H8);
        return saturate(a, r, (a ^ r) & (a ^ b), H8, 8);
    }

    @OpCodeIdentifier(OpCode.I8x16_SUB_SAT_U)
    public static long I8x16_SUB_SAT_U(long a, long b) {
        return sub(a, b, H8) & ~I8x16_LT_U(a, b);
    }

    @OpCodeIdentifier(OpCode.I8x16_MIN_S)
    public static long I8x16_MIN_S(long a, long b) {
        return select(I8x16_LT_S(a, b), a, b);
    }

    @OpCodeIdentifier(OpCode.I8x16_MIN_U)
    public static long I8x16_MIN_U(long a, long b) {
        return select(I8x16_LT_U(a, b), a, b);
    }

    @OpCodeIdentifier(OpCode.I8x16_MAX_S)
    public static long I8x16_MAX_S(long a, long b) {
        return select(I8x16_LT_S(a, b), b, a);
    }

    @OpCodeIdentifier(OpCode.I8x16_MAX_U)
    public static long I8x16_MAX_U(long a, long b) {
        return select(I8x16_LT_U(a, b), b, a);
    }

    @OpCodeIdentifier(OpCode.I8x16_AVGR_U)
    public static long I8x16_AVGR_U(long a, long b) {
        return (a | b) - (((a ^ b) >>> 1) & ~H8);
    }

    // i16x8

    @OpCodeIdentifier(OpCode.I16x8_SPLAT)
    public static long I16x8_SPLAT(int x) {
        return (x & 0xFFFFL) * L16;
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTRACT_LANE_S)
    public static int I16x8_EXTRACT_LANE_S(long w, int lane) {
        return (short) (w >>> (lane * 16));
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTRACT_LANE_U)
    public static int I16x8_EXTRACT_LANE_U(long w, int lane) {
        return (int) (w >>> (lane * 16)) & 0xFFFF;
    }

    @OpCodeIdentifier(OpCode.I16x8_REPLACE_LANE)
    public static long I16x8_REPLACE_LANE(long w, int lane, int x) {
        int s = lane * 16;
        return (w & ~(0xFFFFL << s)) | ((x & 0xFFFFL) << s);
    }

    @OpCodeIdentifier(OpCode.I16x8_EQ)
    public static long I16x8_EQ(long a, long b) {
        return expand(eq(a, b, H16), 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_NE)
    public static long I16x8_NE(long a, long b) {
        return ~I16x8_EQ(a, b);
    }

    @OpCodeIdentifier(OpCode.I16x8_LT_S)
    public static long I16x8_LT_S(long a, long b) {
        return expand(lts(a, b, H16), 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_LT_U)
    public static long I16x8_LT_U(long a, long b) {
        return expand(ltu(a, b, H16), 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_GT_S)
    public static long I16x8_GT_S(long a, long b) {
        return I16x8_LT_S(b, a);
    }

    @OpCodeIdentifier(OpCode.I16x8_GT_U)
    public static long I16x8_GT_U(long a, long b) {
        return I16x8_LT_U(b, a);
    }

    @OpCodeIdentifier(OpCode.I16x8_LE_S)
    public static long I16x8_LE_S(long a, long b) {
        return ~I16x8_LT_S(b, a);
    }

    @OpCodeIdentifier(OpCode.I16x8_LE_U)
    public static long I16x8_LE_U(long a, long b) {
        return ~I16x8_LT_U(b, a);
    }

    @OpCodeIdentifier(OpCode.I16x8_GE_S)
    public static long I16x8_GE_S(long a, long b) {
        return ~I16x8_LT_S(a, b);
    }

    @OpCodeIdentifier(OpCode.I16x8_GE_U)
    public static long I16x8_GE_U(long a, long b) {
        return ~I16x8_LT_U(a, b);
    }

    @OpCodeIdentifier(OpCode.I16x8_ABS)
    public static long I16x8_ABS(long a) {
        return abs(a, H16, 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_NEG)
    public static long I16x8_NEG(long a) {
        return sub(0, a, H16);
    }

    @OpCodeIdentifier(OpCode.I16x8_Q15MULR_SAT_S)
    public static long I16x8_Q15MULR_SAT_S(long a, long b) {
        long r = 0;
        for (int s = 0; s < 64; s += 16) {
            int x = ((short) (a >>> s) * (short) (b >>> s) + 0x4000) >> 15;
            r |= (Math.min(x, Short.MAX_VALUE) & 0xFFFFL) << s;
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I16x8_ALL_TRUE)
    public static int I16x8_ALL_TRUE(long lo, long hi) {
        return (nonzero(lo, H16) & nonzero(hi, H16)) == H16 ? 1 : 0;
    }

    private static int signBits16(long w) {
        return (int) (((w >>> 15) & 1) | ((w >>> 30) & 2) | ((w >>> 45) & 4) | ((w >>> 60) & 8));
    }

    @OpCodeIdentifier(OpCode.I16x8_BITMASK)
    public static int I16x8_BITMASK(long lo, long hi) {
        return signBits16(lo) | (signBits16(hi) << 4);
    }

    private static long narrow32(long w0, long w1, int min, int max) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int x = (int) ((i < 2 ? w0 : w1) >>> ((i & 1) * 32));
            r |= (Math.min(Math.max(x, min), max) & 0xFFFFL) << (i * 16);
        }
        return r;
    }

    // word 0 of the result from the words of a, word 1 from the words of b
    @OpCodeIdentifier(OpCode.I16x8_NARROW_I32x4_S)
    public static long I16x8_NARROW_I32x4_S(long w0, long w1) {
        return narrow32(w0, w1, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    @OpCodeIdentifier(OpCode.I16x8_NARROW_I32x4_U)
    public static long I16x8_NARROW_I32x4_U(long w0, long w1) {
        return narrow32(w0, w1, 0, 0xFFFF);
    }

    // word w of the result from bytes 4 * w to 4 * w + 3 of src, the low or high word of a
    @OpCodeIdentifier(OpCode.I16x8_EXTEND_LOW_I8x16_S)
    public static long I16x8_EXTEND_LOW_I8x16_S(long src, int w) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            r |= ((byte) (src >>> ((w * 4 + i) * 8)) & 0xFFFFL) << (i * 16);
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTEND_HIGH_I8x16_S)
    public static long I16x8_EXTEND_HIGH_I8x16_S(long src, int w) {
        return I16x8_EXTEND_LOW_I8x16_S(src, w);
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTEND_LOW_I8x16_U)
    public static long I16x8_EXTEND_LOW_I8x16_U(long src, int w) {
        long x = (src >>> (w * 32)) & M32;
        x = (x | (x << 16)) & 0x0000FFFF0000FFFFL;
        return (x | (x << 8)) & 0x00FF00FF00FF00FFL;
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTEND_HIGH_I8x16_U)
    public static long I16x8_EXTEND_HIGH_I8x16_U(long src, int w) {
        return I16x8_EXTEND_LOW_I8x16_U(src, w);
    }

    @OpCodeIdentifier(OpCode.I16x8_SHL)
    public static long I16x8_SHL(long a, int n) {
        n &= 15;
        return (a << n) & (((0xFFFFL << n) & 0xFFFF) * L16);
    }

    @OpCodeIdentifier(OpCode.I16x8_SHR_S)
    public static long I16x8_SHR_S(long a, int n) {
        return shr(a, n & 15, 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_SHR_U)
    public static long I16x8_SHR_U(long a, int n) {
        n &= 15;
        return (a >>> n) & ((0xFFFFL >>> n) * L16);
    }

    @OpCodeIdentifier(OpCode.I16x8_ADD)
    public static long I16x8_ADD(long a, long b) {
        return add(a, b, H16);
    }

    @OpCodeIdentifier(OpCode.I16x8_ADD_SAT_S)
    public static long I16x8_ADD_SAT_S(long a, long b) {
        long r = add(a, b, H16);
        return saturate(a, r, (a ^ r) & (b ^ r), H16, 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_ADD_SAT_U)
    public static long I16x8_ADD_SAT_U(long a, long b) {
        long s = add(a, b, H16);
        return s | expand(((a & b) | ((a | b) & ~s)) & H16, 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_SUB)
    public static long I16x8_SUB(long a, long b) {
        return sub(a, b, H16);
    }

    @OpCodeIdentifier(OpCode.I16x8_SUB_SAT_S)
    public static long I16x8_SUB_SAT_S(long a, long b) {
        long r = sub(a, b, H16);
        return saturate(a, r, (a ^ r) & (a ^ b), H16, 16);
    }

    @OpCodeIdentifier(OpCode.I16x8_SUB_SAT_U)
    public static long I16x8_SUB_SAT_U(long a, long b) {
        return sub(a, b, H16) & ~I16x8_LT_U(a, b);
    }

    @OpCodeIdentifier(OpCode.I16x8_MUL)
    public static long I16x8_MUL(long a, long b) {
        long r = 0;
        for (int s = 0; s < 64; s += 16) {
            r |= (((a >>> s) * (b >>> s)) & 0xFFFF) << s;
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I16x8_MIN_S)
    public static long I16x8_MIN_S(long a, long b) {
        return select(I16x8_LT_S(a, b), a, b);
    }

    @OpCodeIdentifier(OpCode.I16x8_MIN_U)
    public static long I16x8_MIN_U(long a, long b) {
        return select(I16x8_LT_U(a, b), a, b);
    }

    @OpCodeIdentifier(OpCode.I16x8_MAX_S)
    public static long I16x8_MAX_S(long a, long b) {
        return select(I16x8_LT_S(a, b), b, a);
    }

    @OpCodeIdentifier(OpCode.I16x8_MAX_U)
    public static long I16x8_MAX_U(long a, long b) {
        return select(I16x8_LT_U(a, b), b, a);
    }

    @OpCodeIdentifier(OpCode.I16x8_AVGR_U)
    public static long I16x8_AVGR_U(long a, long b) {
        return (a | b) - (((a ^ b) >>> 1) & ~H16);
    }

    // word w of the result from bytes 4 * w to 4 * w + 3 of the low or high words of a and b
    @OpCodeIdentifier(OpCode.I16x8_EXTMUL_LOW_I8x16_S)
    public static long I16x8_EXTMUL_LOW_I8x16_S(long a, long b, int w) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int s = (w * 4 + i) * 8;
            r |= (((byte) (a >>> s) * (byte) (b >>> s)) & 0xFFFFL) << (i * 16);
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTMUL_HIGH_I8x16_S)
    public static long I16x8_EXTMUL_HIGH_I8x16_S(long a, long b, int w) {
        return I16x8_EXTMUL_LOW_I8x16_S(a, b, w);
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTMUL_LOW_I8x16_U)
    public static long I16x8_EXTMUL_LOW_I8x16_U(long a, long b, int w) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int s = (w * 4 + i) * 8;
            r |= (((a >>> s) & 0xFF) * ((b >>> s) & 0xFF)) << (i * 16);
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTMUL_HIGH_I8x16_U)
    public static long I16x8_EXTMUL_HIGH_I8x16_U(long a, long b, int w) {
        return I16x8_EXTMUL_LOW_I8x16_U(a, b, w);
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTADD_PAIRWISE_I8x16_S)
    public static long I16x8_EXTADD_PAIRWISE_I8x16_S(long a) {
        long r = 0;
        for (int s = 0; s < 64; s += 16) {
            r |= (((byte) (a >>> s) + (byte) (a >>> (s + 8))) & 0xFFFFL) << s;
        }
        return r;
    }

    @OpCodeIdentifier(OpCode.I16x8_EXTADD_PAIRWISE_I8x16_U)
    public static long I16x8_EXTADD_PAIRWISE_I8x16_U(long a) {
        return (a & 0x00FF00FF00FF00FFL) + ((a >>> 8) & 0x00FF00FF00FF00FFL);
    }

    // i32x4

    @OpCodeIdentifier(OpCode.I32x4_SPLAT)
    public static long I32x4_SPLAT(int x) {
        return (x & M32) * L32;
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTRACT_LANE)
    public static int I32x4_EXTRACT_LANE(long w, int lane) {
        return (int) (w >>> (lane * 32));
    }

    @OpCodeIdentifier(OpCode.I32x4_REPLACE_LANE)
    public static long I32x4_REPLACE_LANE(long w, int lane, int x) {
        int s = lane * 32;
        return (w & ~(M32 << s)) | ((x & M32) << s);
    }

    @OpCodeIdentifier(OpCode.I32x4_EQ)
    public static long I32x4_EQ(long a, long b) {
        return pack32(bool((int) a == (int) b), bool((int) (a >>> 32) == (int) (b >>> 32)));
    }

    @OpCodeIdentifier(OpCode.I32x4_NE)
    public static long I32x4_NE(long a, long b) {
        return ~I32x4_EQ(a, b);
    }

    @OpCodeIdentifier(OpCode.I32x4_LT_S)
    public static long I32x4_LT_S(long a, long b) {
        return pack32(bool((int) a < (int) b), bool((int) (a >>> 32) < (int) (b >>> 32)));
    }

    @OpCodeIdentifier(OpCode.I32x4_LT_U)
    public static long I32x4_LT_U(long a, long b) {
        return pack32(
                bool(Integer.compareUnsigned((int) a, (int) b) < 0),
                bool(Integer.compareUnsigned((int) (a >>> 32), (int) (b >>> 32)) < 0));
    }

    @OpCodeIdentifier(OpCode.I32x4_GT_S)
    public static long I32x4_GT_S(long a, long b) {
        return I32x4_LT_S(b, a);
    }

    @OpCodeIdentifier(OpCode.I32x4_GT_U)
    public static long I32x4_GT_U(long a, long b) {
        return I32x4_LT_U(b, a);
    }

    @OpCodeIdentifier(OpCode.I32x4_LE_S)
    public static long I32x4_LE_S(long a, long b) {
        return ~I32x4_LT_S(b, a);
    }

    @OpCodeIdentifier(OpCode.I32x4_LE_U)
    public static long I32x4_LE_U(long a, long b) {
        return ~I32x4_LT_U(b, a);
    }

    @OpCodeIdentifier(OpCode.I32x4_GE_S)
    public static long I32x4_GE_S(long a, long b) {
        return ~I32x4_LT_S(a, b);
    }

    @OpCodeIdentifier(OpCode.I32x4_GE_U)
    public static long I32x4_GE_U(long a, long b) {
        return ~I32x4_LT_U(a, b);
    }

    @OpCodeIdentifier(OpCode.I32x4_ABS)
    public static long I32x4_ABS(long a) {
        return pack32(Math.abs((int) a), Math.abs((int) (a >>> 32)));
    }

    @OpCodeIdentifier(OpCode.I32x4_NEG)
    public static long I32x4_NEG(long a) {
        return pack32(-(int) a, -(int) (a >>> 32));
    }

    @OpCodeIdentifier(OpCode.I32x4_ALL_TRUE)
    public static int I32x4_ALL_TRUE(long lo, long hi) {
        return (int) lo != 0 && (lo >>> 32) != 0 && (int) hi != 0 && (hi >>> 32) != 0 ? 1 : 0;
    }

    @OpCodeIdentifier(OpCode.I32x4_BITMASK)
    public static int I32x4_BITMASK(long lo, long hi) {
        return (int)
                (((lo >>> 31) & 1) | ((lo >>> 62) & 2) | ((hi >>> 29) & 4) | ((hi >>> 60) & 8));
    }

    // word w of the result from shorts 2 * w and 2 * w + 1 of src, the low or high word of a
    @OpCodeIdentifier(OpCode.I32x4_EXTEND_LOW_I16x8_S)
    public static long I32x4_EXTEND_LOW_I16x8_S(long src, int w) {
        long x = src >>> (w * 32);
        return pack32((short) x, (short) (x >>> 16));
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTEND_HIGH_I16x8_S)
    public static long I32x4_EXTEND_HIGH_I16x8_S(long src, int w) {
        return I32x4_EXTEND_LOW_I16x8_S(src, w);
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTEND_LOW_I16x8_U)
    public static long I32x4_EXTEND_LOW_I16x8_U(long src, int w) {
        long x = src >>> (w * 32);
        return (x & 0xFFFF) | ((x & 0xFFFF0000L) << 16);
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTEND_HIGH_I16x8_U)
    public static long I32x4_EXTEND_HIGH_I16x8_U(long src, int w) {
        return I32x4_EXTEND_LOW_I16x8_U(src, w);
    }

    @OpCodeIdentifier(OpCode.I32x4_SHL)
    public static long I32x4_SHL(long a, int n) {
        n &= 31;
        return (a << n) & (((M32 << n) & M32) * L32);
    }

    @OpCodeIdentifier(OpCode.I32x4_SHR_S)
    public static long I32x4_SHR_S(long a, int n) {
        n &= 31;
        return ((a >> n) & ~M32) | (((int) a >> n) & M32);
    }

    @OpCodeIdentifier(OpCode.I32x4_SHR_U)
    public static long I32x4_SHR_U(long a, int n) {
        n &= 31;
        return (a >>> n) & ((M32 >>> n) * L32);
    }

    @OpCodeIdentifier(OpCode.I32x4_ADD)
    public static long I32x4_ADD(long a, long b) {
        return add(a, b, H32);
    }

    @OpCodeIdentifier(OpCode.I32x4_SUB)
    public static long I32x4_SUB(long a, long b) {
        return sub(a, b, H32);
    }

    @OpCodeIdentifier(OpCode.I32x4_MUL)
    public static long I32x4_MUL(long a, long b) {
        return pack32((int) a * (int) b, (int) (a >>> 32) * (int) (b >>> 32));
    }

    @OpCodeIdentifier(OpCode.I32x4_MIN_S)
    public static long I32x4_MIN_S(long a, long b) {
        return select(I32x4_LT_S(a, b), a, b);
    }

    @OpCodeIdentifier(OpCode.I32x4_MIN_U)
    public static long I32x4_MIN_U(long a, long b) {
        return select(I32x4_LT_U(a, b), a, b);
    }

    @OpCodeIdentifier(OpCode.I32x4_MAX_S)
    public static long I32x4_MAX_S(long a, long b) {
        return select(I32x4_LT_S(a, b), b, a);
    }

    @OpCodeIdentifier(OpCode.I32x4_MAX_U)
    public static long I32x4_MAX_U(long a, long b) {
        return select(I32x4_LT_U(a, b), b, a);
    }

    @OpCodeIdentifier(OpCode.I32x4_DOT_I16x8_S)
    public static long I32x4_DOT_I16x8_S(long a, long b) {
        return pack32(
                (short) a * (short) b + (short) (a >>> 16) * (short) (b >>> 16),
                (short) (a >>> 32) * (short) (b >>> 32) + (short) (a >>> 48) * (short) (b >>> 48));
    }

    // word w of the result from shorts 2 * w and 2 * w + 1 of the low or high words of a and b
    @OpCodeIdentifier(OpCode.I32x4_EXTMUL_LOW_I16x8_S)
    public static long I32x4_EXTMUL_LOW_I16x8_S(long a, long b, int w) {
        int s = w * 32;
        return pack32(
                (short) (a >>> s) * (short) (b >>> s),
                (short) (a >>> (s + 16)) * (short) (b >>> (s + 16)));
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTMUL_HIGH_I16x8_S)
    public static long I32x4_EXTMUL_HIGH_I16x8_S(long a, long b, int w) {
        return I32x4_EXTMUL_LOW_I16x8_S(a, b, w);
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTMUL_LOW_I16x8_U)
    public static long I32x4_EXTMUL_LOW_I16x8_U(long a, long b, int w) {
        int s = w * 32;
        return pack32(
                (int) (((a >>> s) & 0xFFFF) * ((b >>> s) & 0xFFFF)),
                (int) (((a >>> (s + 16)) & 0xFFFF) * ((b >>> (s + 16)) & 0xFFFF)));
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTMUL_HIGH_I16x8_U)
    public static long I32x4_EXTMUL_HIGH_I16x8_U(long a, long b, int w) {
        return I32x4_EXTMUL_LOW_I16x8_U(a, b, w);
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTADD_PAIRWISE_I16x8_S)
    public static long I32x4_EXTADD_PAIRWISE_I16x8_S(long a) {
        return pack32((short) a + (short) (a >>> 16), (short) (a >>> 32) + (short) (a >>> 48));
    }

    @OpCodeIdentifier(OpCode.I32x4_EXTADD_PAIRWISE_I16x8_U)
    public static long I32x4_EXTADD_PAIRWISE_I16x8_U(long a) {
        return (a & 0x0000FFFF0000FFFFL) + ((a >>> 16) & 0x0000FFFF0000FFFFL);
    }

    @OpCodeIdentifier(OpCode.I32x4_TRUNC_SAT_F32X4_S)
    public static long I32x4_TRUNC_SAT_F32X4_S(long a) {
        return pack32((int) f32(a, 0), (int) f32(a, 1));
    }

    private static int truncSatU(double d) {
        return (int) Math.min(Math.max((long) d, 0), M32);
    }

    @OpCodeIdentifier(OpCode.I32x4_TRUNC_SAT_F32X4_U)
    public static long I32x4_TRUNC_SAT_F32X4_U(long a) {
        return pack32(truncSatU(f32(a, 0)), truncSatU(f32(a, 1)));
    }

    // the low word of the result from both words of a; the high word is zero
    @OpCodeIdentifier(OpCode.I32x4_TRUNC_SAT_F64x2_S_ZERO)
    public static long I32x4_TRUNC_SAT_F64x2_S_ZERO(long lo, long hi) {
        return pack32((int) f64(lo), (int) f64(hi));
    }

    @OpCodeIdentifier(OpCode.I32x4_TRUNC_SAT_F64x2_U_ZERO)
    public static long I32x4_TRUNC_SAT_F64x2_U_ZERO(long lo, long hi) {
        return pack32(truncSatU(f64(lo)), truncSatU(f64(hi)));
    }

    // i64x2: one lane per word

    @OpCodeIdentifier(OpCode.I64x2_EQ)
    public static long I64x2_EQ(long a, long b) {
        return dbool(a == b);
    }

    @OpCodeIdentifier(OpCode.I64x2_NE)
    public static long I64x2_NE(long a, long b) {
        return dbool(a != b);
    }

    @OpCodeIdentifier(OpCode.I64x2_LT_S)
    public static long I64x2_LT_S(long a, long b) {
        return dbool(a < b);
    }

    @OpCodeIdentifier(OpCode.I64x2_GT_S)
    public static long I64x2_GT_S(long a, long b) {
        return dbool(a > b);
    }

    @OpCodeIdentifier(OpCode.I64x2_LE_S)
    public static long I64x2_LE_S(long a, long b) {
        return dbool(a <= b);
    }

    @OpCodeIdentifier(OpCode.I64x2_GE_S)
    public static long I64x2_GE_S(long a, long b) {
        return dbool(a >= b);
    }

    @OpCodeIdentifier(OpCode.I64x2_ABS)
    public static long I64x2_ABS(long a) {
        return Math.abs(a);
    }

    @OpCodeIdentifier(OpCode.I64x2_ALL_TRUE)
    public static int I64x2_ALL_TRUE(long lo, long hi) {
        return lo != 0 && hi != 0 ? 1 : 0;
    }

    @OpCodeIdentifier(OpCode.I64x2_BITMASK)
    public static int I64x2_BITMASK(long lo, long hi) {
        return (int) ((lo >>> 63) | ((hi >>> 63) << 1));
    }

    // word w of the result from i32 lane w of src, the low or high word of a
    @OpCodeIdentifier(OpCode.I64x2_EXTEND_LOW_I32x4_S)
    public static long I64x2_EXTEND_LOW_I32x4_S(long src, int w) {
        return (int) (src >>> (w * 32));
    }

    @OpCodeIdentifier(OpCode.I64x2_EXTEND_HIGH_I32x4_S)
    public static long I64x2_EXTEND_HIGH_I32x4_S(long src, int w) {
        return I64x2_EXTEND_LOW_I32x4_S(src, w);
    }

    @OpCodeIdentifier(OpCode.I64x2_EXTEND_LOW_I32x4_U)
    public static long I64x2_EXTEND_LOW_I32x4_U(long src, int w) {
        return (src >>> (w * 32)) & M32;
    }

    @OpCodeIdentifier(OpCode.I64x2_EXTEND_HIGH_I32x4_U)
    public static long I64x2_EXTEND_HIGH_I32x4_U(long src, int w) {
        return I64x2_EXTEND_LOW_I32x4_U(src, w);
    }

    // word w of the result from i32 lane w of the low or high words of a and b
    @OpCodeIdentifier(OpCode.I64x2_EXTMUL_LOW_I32x4_S)
    public static long I64x2_EXTMUL_LOW_I32x4_S(long a, long b, int w) {
        return (long) (int) (a >>> (w * 32)) * (int) (b >>> (w * 32));
    }

    @OpCodeIdentifier(OpCode.I64x2_EXTMUL_HIGH_I32x4_S)
    public static long I64x2_EXTMUL_HIGH_I32x4_S(long a, long b, int w) {
        return I64x2_EXTMUL_LOW_I32x4_S(a, b, w);
    }

    @OpCodeIdentifier(OpCode.I64x2_EXTMUL_LOW_I32x4_U)
    public static long I64x2_EXTMUL_LOW_I32x4_U(long a, long b, int w) {
        return ((a >>> (w * 32)) & M32) * ((b >>> (w * 32)) & M32);
    }

    @OpCodeIdentifier(OpCode.I64x2_EXTMUL_HIGH_I32x4_U)
    public static long I64x2_EXTMUL_HIGH_I32x4_U(long a, long b, int w) {
        return I64x2_EXTMUL_LOW_I32x4_U(a, b, w);
    }

    // f32x4

    @OpCodeIdentifier(OpCode.F32x4_SPLAT)
    public static long F32x4_SPLAT(float x) {
        return (Float.floatToRawIntBits(x) & M32) * L32;
    }

    @OpCodeIdentifier(OpCode.F32x4_EXTRACT_LANE)
    public static float F32x4_EXTRACT_LANE(long w, int lane) {
        return f32(w, lane);
    }

    @OpCodeIdentifier(OpCode.F32x4_REPLACE_LANE)
    public static long F32x4_REPLACE_LANE(long w, int lane, float x) {
        return I32x4_REPLACE_LANE(w, lane, Float.floatToRawIntBits(x));
    }

    @OpCodeIdentifier(OpCode.F32x4_EQ)
    public static long F32x4_EQ(long a, long b) {
        return pack32(bool(f32(a, 0) == f32(b, 0)), bool(f32(a, 1) == f32(b, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_NE)
    public static long F32x4_NE(long a, long b) {
        return pack32(bool(f32(a, 0) != f32(b, 0)), bool(f32(a, 1) != f32(b, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_LT)
    public static long F32x4_LT(long a, long b) {
        return pack32(bool(f32(a, 0) < f32(b, 0)), bool(f32(a, 1) < f32(b, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_GT)
    public static long F32x4_GT(long a, long b) {
        return pack32(bool(f32(a, 0) > f32(b, 0)), bool(f32(a, 1) > f32(b, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_LE)
    public static long F32x4_LE(long a, long b) {
        return pack32(bool(f32(a, 0) <= f32(b, 0)), bool(f32(a, 1) <= f32(b, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_GE)
    public static long F32x4_GE(long a, long b) {
        return pack32(bool(f32(a, 0) >= f32(b, 0)), bool(f32(a, 1) >= f32(b, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_ABS)
    public static long F32x4_ABS(long a) {
        return a & ~H32;
    }

    @OpCodeIdentifier(OpCode.F32x4_NEG)
    public static long F32x4_NEG(long a) {
        return a ^ H32;
    }

    // through double: exact for sqrt and the roundings of a float
    @OpCodeIdentifier(OpCode.F32x4_SQRT)
    public static long F32x4_SQRT(long a) {
        return bits((float) Math.sqrt(f32(a, 0)), (float) Math.sqrt(f32(a, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_CEIL)
    public static long F32x4_CEIL(long a) {
        return bits((float) Math.ceil(f32(a, 0)), (float) Math.ceil(f32(a, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_FLOOR)
    public static long F32x4_FLOOR(long a) {
        return bits((float) Math.floor(f32(a, 0)), (float) Math.floor(f32(a, 1)));
    }

    private static double trunc(double x) {
        return x < 0 ? Math.ceil(x) : Math.floor(x);
    }

    @OpCodeIdentifier(OpCode.F32x4_TRUNC)
    public static long F32x4_TRUNC(long a) {
        return bits((float) trunc(f32(a, 0)), (float) trunc(f32(a, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_NEAREST)
    public static long F32x4_NEAREST(long a) {
        return bits((float) Math.rint(f32(a, 0)), (float) Math.rint(f32(a, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_ADD)
    public static long F32x4_ADD(long a, long b) {
        return bits(f32(a, 0) + f32(b, 0), f32(a, 1) + f32(b, 1));
    }

    @OpCodeIdentifier(OpCode.F32x4_SUB)
    public static long F32x4_SUB(long a, long b) {
        return bits(f32(a, 0) - f32(b, 0), f32(a, 1) - f32(b, 1));
    }

    @OpCodeIdentifier(OpCode.F32x4_MUL)
    public static long F32x4_MUL(long a, long b) {
        return bits(f32(a, 0) * f32(b, 0), f32(a, 1) * f32(b, 1));
    }

    @OpCodeIdentifier(OpCode.F32x4_DIV)
    public static long F32x4_DIV(long a, long b) {
        return bits(f32(a, 0) / f32(b, 0), f32(a, 1) / f32(b, 1));
    }

    @OpCodeIdentifier(OpCode.F32x4_MIN)
    public static long F32x4_MIN(long a, long b) {
        return bits(Math.min(f32(a, 0), f32(b, 0)), Math.min(f32(a, 1), f32(b, 1)));
    }

    @OpCodeIdentifier(OpCode.F32x4_MAX)
    public static long F32x4_MAX(long a, long b) {
        return bits(Math.max(f32(a, 0), f32(b, 0)), Math.max(f32(a, 1), f32(b, 1)));
    }

    // pmin and pmax pick an operand lane, bits included
    @OpCodeIdentifier(OpCode.F32x4_PMIN)
    public static long F32x4_PMIN(long a, long b) {
        long lo = f32(b, 0) < f32(a, 0) ? b : a;
        long hi = f32(b, 1) < f32(a, 1) ? b : a;
        return (lo & M32) | (hi & ~M32);
    }

    @OpCodeIdentifier(OpCode.F32x4_PMAX)
    public static long F32x4_PMAX(long a, long b) {
        long lo = f32(a, 0) < f32(b, 0) ? b : a;
        long hi = f32(a, 1) < f32(b, 1) ? b : a;
        return (lo & M32) | (hi & ~M32);
    }

    @OpCodeIdentifier(OpCode.F32x4_CONVERT_I32x4_S)
    public static long F32x4_CONVERT_I32x4_S(long a) {
        return bits((float) (int) a, (float) (int) (a >>> 32));
    }

    @OpCodeIdentifier(OpCode.F32x4_CONVERT_I32x4_U)
    public static long F32x4_CONVERT_I32x4_U(long a) {
        return bits((float) (a & M32), (float) (a >>> 32));
    }

    // the low word of the result from both words of a; the high word is zero
    @OpCodeIdentifier(OpCode.F32x4_DEMOTE_LOW_F64x2_ZERO)
    public static long F32x4_DEMOTE_LOW_F64x2_ZERO(long lo, long hi) {
        return bits((float) f64(lo), (float) f64(hi));
    }

    // f64x2: one lane per word

    @OpCodeIdentifier(OpCode.F64x2_SPLAT)
    public static long F64x2_SPLAT(double x) {
        return dbits(x);
    }

    @OpCodeIdentifier(OpCode.F64x2_EXTRACT_LANE)
    public static double F64x2_EXTRACT_LANE(long w, int lane) {
        return f64(w);
    }

    @OpCodeIdentifier(OpCode.F64x2_REPLACE_LANE)
    public static long F64x2_REPLACE_LANE(long w, int lane, double x) {
        return dbits(x);
    }

    @OpCodeIdentifier(OpCode.F64x2_EQ)
    public static long F64x2_EQ(long a, long b) {
        return dbool(f64(a) == f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_NE)
    public static long F64x2_NE(long a, long b) {
        return dbool(f64(a) != f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_LT)
    public static long F64x2_LT(long a, long b) {
        return dbool(f64(a) < f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_GT)
    public static long F64x2_GT(long a, long b) {
        return dbool(f64(a) > f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_LE)
    public static long F64x2_LE(long a, long b) {
        return dbool(f64(a) <= f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_GE)
    public static long F64x2_GE(long a, long b) {
        return dbool(f64(a) >= f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_ABS)
    public static long F64x2_ABS(long a) {
        return a & Long.MAX_VALUE;
    }

    @OpCodeIdentifier(OpCode.F64x2_NEG)
    public static long F64x2_NEG(long a) {
        return a ^ Long.MIN_VALUE;
    }

    @OpCodeIdentifier(OpCode.F64x2_SQRT)
    public static long F64x2_SQRT(long a) {
        return dbits(Math.sqrt(f64(a)));
    }

    @OpCodeIdentifier(OpCode.F64x2_CEIL)
    public static long F64x2_CEIL(long a) {
        return dbits(Math.ceil(f64(a)));
    }

    @OpCodeIdentifier(OpCode.F64x2_FLOOR)
    public static long F64x2_FLOOR(long a) {
        return dbits(Math.floor(f64(a)));
    }

    @OpCodeIdentifier(OpCode.F64x2_TRUNC)
    public static long F64x2_TRUNC(long a) {
        return dbits(trunc(f64(a)));
    }

    @OpCodeIdentifier(OpCode.F64x2_NEAREST)
    public static long F64x2_NEAREST(long a) {
        return dbits(Math.rint(f64(a)));
    }

    @OpCodeIdentifier(OpCode.F64x2_ADD)
    public static long F64x2_ADD(long a, long b) {
        return dbits(f64(a) + f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_SUB)
    public static long F64x2_SUB(long a, long b) {
        return dbits(f64(a) - f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_MUL)
    public static long F64x2_MUL(long a, long b) {
        return dbits(f64(a) * f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_DIV)
    public static long F64x2_DIV(long a, long b) {
        return dbits(f64(a) / f64(b));
    }

    @OpCodeIdentifier(OpCode.F64x2_MIN)
    public static long F64x2_MIN(long a, long b) {
        return dbits(Math.min(f64(a), f64(b)));
    }

    @OpCodeIdentifier(OpCode.F64x2_MAX)
    public static long F64x2_MAX(long a, long b) {
        return dbits(Math.max(f64(a), f64(b)));
    }

    @OpCodeIdentifier(OpCode.F64x2_PMIN)
    public static long F64x2_PMIN(long a, long b) {
        return f64(b) < f64(a) ? b : a;
    }

    @OpCodeIdentifier(OpCode.F64x2_PMAX)
    public static long F64x2_PMAX(long a, long b) {
        return f64(a) < f64(b) ? b : a;
    }

    // word w of the result from f32 or i32 lane w of the low word of a
    @OpCodeIdentifier(OpCode.F64x2_PROMOTE_LOW_F32x4)
    public static long F64x2_PROMOTE_LOW_F32x4(long src, int w) {
        return dbits((double) f32(src, w));
    }

    @OpCodeIdentifier(OpCode.F64x2_CONVERT_LOW_I32x4_S)
    public static long F64x2_CONVERT_LOW_I32x4_S(long src, int w) {
        return dbits((double) (int) (src >>> (w * 32)));
    }

    @OpCodeIdentifier(OpCode.F64x2_CONVERT_LOW_I32x4_U)
    public static long F64x2_CONVERT_LOW_I32x4_U(long src, int w) {
        return dbits((double) ((src >>> (w * 32)) & M32));
    }
}
