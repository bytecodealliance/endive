package run.endive.redline.experimental.api.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Hardware atomics on a native memory's mapping, via a direct buffer; callers check bounds. */
public final class NativeAtomics {

    private static final VarHandle INT =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final int ADD = 0;
    private static final int AND = 1;
    private static final int OR = 2;
    private static final int XOR = 3;
    private static final int XCHG = 4;

    private final ByteBuffer buffer;

    public NativeAtomics(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    public int readInt(int addr) {
        return (int) INT.getVolatile(buffer, addr);
    }

    public long readLong(int addr) {
        return (long) LONG.getVolatile(buffer, addr);
    }

    public short readShort(int addr) {
        return (short) narrowGet(addr, 16);
    }

    public byte readByte(int addr) {
        return (byte) narrowGet(addr, 8);
    }

    public void writeInt(int addr, int value) {
        INT.setVolatile(buffer, addr, value);
    }

    public void writeLong(int addr, long value) {
        LONG.setVolatile(buffer, addr, value);
    }

    public void writeShort(int addr, short value) {
        narrowUpdate(addr, 16, XCHG, value);
    }

    public void writeByte(int addr, byte value) {
        narrowUpdate(addr, 8, XCHG, value);
    }

    public int addInt(int addr, int delta) {
        return (int) INT.getAndAdd(buffer, addr, delta);
    }

    public int andInt(int addr, int mask) {
        return (int) INT.getAndBitwiseAnd(buffer, addr, mask);
    }

    public int orInt(int addr, int mask) {
        return (int) INT.getAndBitwiseOr(buffer, addr, mask);
    }

    public int xorInt(int addr, int mask) {
        return (int) INT.getAndBitwiseXor(buffer, addr, mask);
    }

    public int xchgInt(int addr, int value) {
        return (int) INT.getAndSet(buffer, addr, value);
    }

    public int cmpxchgInt(int addr, int expected, int replacement) {
        return (int) INT.compareAndExchange(buffer, addr, expected, replacement);
    }

    public long addLong(int addr, long delta) {
        return (long) LONG.getAndAdd(buffer, addr, delta);
    }

    public long andLong(int addr, long mask) {
        return (long) LONG.getAndBitwiseAnd(buffer, addr, mask);
    }

    public long orLong(int addr, long mask) {
        return (long) LONG.getAndBitwiseOr(buffer, addr, mask);
    }

    public long xorLong(int addr, long mask) {
        return (long) LONG.getAndBitwiseXor(buffer, addr, mask);
    }

    public long xchgLong(int addr, long value) {
        return (long) LONG.getAndSet(buffer, addr, value);
    }

    public long cmpxchgLong(int addr, long expected, long replacement) {
        return (long) LONG.compareAndExchange(buffer, addr, expected, replacement);
    }

    public short addShort(int addr, short delta) {
        return (short) narrowUpdate(addr, 16, ADD, delta);
    }

    public short andShort(int addr, short mask) {
        return (short) narrowUpdate(addr, 16, AND, mask);
    }

    public short orShort(int addr, short mask) {
        return (short) narrowUpdate(addr, 16, OR, mask);
    }

    public short xorShort(int addr, short mask) {
        return (short) narrowUpdate(addr, 16, XOR, mask);
    }

    public short xchgShort(int addr, short value) {
        return (short) narrowUpdate(addr, 16, XCHG, value);
    }

    public short cmpxchgShort(int addr, short expected, short replacement) {
        return (short) narrowCmpxchg(addr, 16, expected, replacement);
    }

    public byte addByte(int addr, byte delta) {
        return (byte) narrowUpdate(addr, 8, ADD, delta);
    }

    public byte andByte(int addr, byte mask) {
        return (byte) narrowUpdate(addr, 8, AND, mask);
    }

    public byte orByte(int addr, byte mask) {
        return (byte) narrowUpdate(addr, 8, OR, mask);
    }

    public byte xorByte(int addr, byte mask) {
        return (byte) narrowUpdate(addr, 8, XOR, mask);
    }

    public byte xchgByte(int addr, byte value) {
        return (byte) narrowUpdate(addr, 8, XCHG, value);
    }

    public byte cmpxchgByte(int addr, byte expected, byte replacement) {
        return (byte) narrowCmpxchg(addr, 8, expected, replacement);
    }

    private int narrowGet(int addr, int bits) {
        int shift = shift(addr, bits);
        return ((int) INT.getVolatile(buffer, addr & ~3) >>> shift) & ((1 << bits) - 1);
    }

    // Byte and short have no atomic VarHandle modes: CAS the enclosing aligned int.
    private int narrowUpdate(int addr, int bits, int op, int operand) {
        int base = addr & ~3;
        int shift = shift(addr, bits);
        int mask = ((1 << bits) - 1) << shift;
        while (true) {
            int word = (int) INT.getVolatile(buffer, base);
            int old = (word & mask) >>> shift;
            int updated = (word & ~mask) | ((apply(op, old, operand) << shift) & mask);
            if (INT.compareAndSet(buffer, base, word, updated)) {
                return old;
            }
        }
    }

    private int narrowCmpxchg(int addr, int bits, int expected, int replacement) {
        int base = addr & ~3;
        int shift = shift(addr, bits);
        int valueMask = (1 << bits) - 1;
        int mask = valueMask << shift;
        while (true) {
            int word = (int) INT.getVolatile(buffer, base);
            int old = (word & mask) >>> shift;
            if (old != (expected & valueMask)) {
                return old;
            }
            int updated = (word & ~mask) | ((replacement & valueMask) << shift);
            if (INT.compareAndSet(buffer, base, word, updated)) {
                return old;
            }
        }
    }

    private static int shift(int addr, int bits) {
        if ((addr & (bits / 8 - 1)) != 0) {
            throw new IllegalStateException("Misaligned atomic access at address " + addr);
        }
        return (addr & 3) * 8;
    }

    private static int apply(int op, int old, int operand) {
        switch (op) {
            case ADD:
                return old + operand;
            case AND:
                return old & operand;
            case OR:
                return old | operand;
            case XOR:
                return old ^ operand;
            default:
                return operand;
        }
    }
}
