package run.endive.runtime;

import static java.lang.Math.min;
import static run.endive.runtime.ConstantEvaluators.computeConstantValue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.ActiveDataSegment;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;

/**
 * Linear memory kept in one contiguous byte array, for memories that are not shared.
 *
 * <p>Every load and store is a single bounds-checked access to that array: there is no page to
 * look up and no access that can straddle two pages. Growing the memory copies it into a larger
 * array, which is why shared memories, whose other threads could still be reading the old array,
 * are rejected: use {@link ByteArrayMemory} for those.
 *
 * <p>The memory can grow to {@link Memory#RUNTIME_MAX_PAGES} pages, the largest size a single
 * Java array can hold.
 */
public final class FlatByteArrayMemory implements Memory {
    // Same note as ByteArrayMemory: catching RuntimeException keeps the accessors short enough to
    // inline, and the JVM's own array bounds check is the wasm bounds check.

    private static final VarHandle SHORT_ARR_HANDLE =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_ARR_HANDLE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle FLOAT_ARR_HANDLE =
            MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_ARR_HANDLE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle DOUBLE_ARR_HANDLE =
            MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.LITTLE_ENDIAN);

    private final MemoryLimits limits;
    private DataSegment[] dataSegments;
    private byte[] buffer;

    public FlatByteArrayMemory(MemoryLimits limits) {
        if (limits.shared()) {
            throw new IllegalArgumentException(
                    "FlatByteArrayMemory cannot back a shared memory; use ByteArrayMemory");
        }
        this.limits = limits;
        this.buffer = new byte[PAGE_SIZE * min(limits.initialPages(), maximumPages())];
    }

    @Override
    public int pages() {
        return buffer.length / PAGE_SIZE;
    }

    @Override
    public int grow(int size) {
        int prevPages = pages();
        int numPages = prevPages + size;
        if (numPages > maximumPages() || numPages < prevPages) {
            return -1;
        }
        if (numPages != prevPages) {
            buffer = Arrays.copyOf(buffer, numPages * PAGE_SIZE);
        }
        return prevPages;
    }

    @Override
    public int initialPages() {
        return limits.initialPages();
    }

    @Override
    public int maximumPages() {
        return min(limits.maximumPages(), RUNTIME_MAX_PAGES);
    }

    @Override
    public boolean shared() {
        return false;
    }

    @Override
    @SuppressWarnings("removal")
    public Object lock(int address) {
        return this;
    }

    @Override
    @SuppressWarnings("removal")
    public int waitOn(int address, int expected, long timeout) {
        throw new WasmEngineException("Attempt to wait on a non-shared memory, not supported.");
    }

    @Override
    @SuppressWarnings("removal")
    public int waitOn(int address, long expected, long timeout) {
        throw new WasmEngineException("Attempt to wait on a non-shared memory, not supported.");
    }

    @Override
    @SuppressWarnings("removal")
    public int notify(int address, int maxThreads) {
        return 0;
    }

    @Override
    public void initialize(Instance instance, DataSegment[] dataSegments) {
        initialize(instance, dataSegments, 0);
    }

    @Override
    public void initialize(Instance instance, DataSegment[] dataSegments, int memoryIndex) {
        this.dataSegments = dataSegments;
        if (dataSegments == null) {
            return;
        }
        for (var s : dataSegments) {
            if (s instanceof ActiveDataSegment) {
                var segment = (ActiveDataSegment) s;
                if (segment.index() != memoryIndex) {
                    continue;
                }
                var data = segment.bytes();
                var offset = (int) computeConstantValue(instance, segment.offsetInstructions())[0];
                if (outOfBounds(offset, data.length, buffer.length)) {
                    throw new UninstantiableException(boundsMessage(offset, data.length));
                }
                write(offset, data, 0, data.length);
            } else if (!(s instanceof PassiveDataSegment)) {
                throw new WasmEngineException("Data segment should be active or passive: " + s);
            }
        }
    }

    @Override
    public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
        write(dest, dataSegments[segmentId].bytes(), offset, size);
    }

    @Override
    public void drop(int segment) {
        dataSegments[segment] = PassiveDataSegment.EMPTY;
    }

    private static boolean outOfBounds(int addr, int size, int limit) {
        return addr < 0
                || size < 0
                || addr > limit
                || (size > 0 && (long) addr + (long) size > (long) limit);
    }

    private String boundsMessage(int addr, int size) {
        return "out of bounds memory access: attempted to access address: "
                + addr
                + " but limit is: "
                + buffer.length
                + " and size: "
                + size;
    }

    private RuntimeException outOfBoundsException(RuntimeException e, int addr, int size) {
        if (e instanceof IndexOutOfBoundsException
                || e instanceof IllegalArgumentException
                || e instanceof NegativeArraySizeException) {
            return new WasmRuntimeException(boundsMessage(addr, size));
        }
        return e;
    }

    @Override
    public void write(int addr, byte[] data, int offset, int size) {
        if (outOfBounds(offset, size, data.length)) {
            throw new WasmRuntimeException(
                    "out of bounds memory access: attempted to read "
                            + size
                            + " bytes at "
                            + offset
                            + " from an array of "
                            + data.length);
        }
        if (outOfBounds(addr, size, buffer.length)) {
            throw new WasmRuntimeException(boundsMessage(addr, size));
        }
        System.arraycopy(data, offset, buffer, addr, size);
    }

    @Override
    public byte read(int addr) {
        try {
            return buffer[addr];
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 1);
        }
    }

    @Override
    public byte[] readBytes(int addr, int len) {
        if (outOfBounds(addr, len, buffer.length)) {
            throw new WasmRuntimeException(boundsMessage(addr, len));
        }
        return Arrays.copyOfRange(buffer, addr, addr + len);
    }

    @Override
    public void writeI32(int addr, int data) {
        try {
            INT_ARR_HANDLE.set(buffer, addr, data);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 4);
        }
    }

    @Override
    public int readInt(int addr) {
        try {
            return (int) INT_ARR_HANDLE.get(buffer, addr);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 4);
        }
    }

    @Override
    public void writeLong(int addr, long data) {
        try {
            LONG_ARR_HANDLE.set(buffer, addr, data);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 8);
        }
    }

    @Override
    public long readLong(int addr) {
        try {
            return (long) LONG_ARR_HANDLE.get(buffer, addr);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 8);
        }
    }

    @Override
    public void writeShort(int addr, short data) {
        try {
            SHORT_ARR_HANDLE.set(buffer, addr, data);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 2);
        }
    }

    @Override
    public short readShort(int addr) {
        try {
            return (short) SHORT_ARR_HANDLE.get(buffer, addr);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 2);
        }
    }

    @Override
    public long readU16(int addr) {
        return readShort(addr) & 0xFFFF;
    }

    @Override
    public void writeByte(int addr, byte data) {
        try {
            buffer[addr] = data;
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 1);
        }
    }

    @Override
    public void writeF32(int addr, float data) {
        try {
            FLOAT_ARR_HANDLE.set(buffer, addr, data);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 4);
        }
    }

    @Override
    public long readF32(int addr) {
        return readInt(addr);
    }

    @Override
    public float readFloat(int addr) {
        try {
            return (float) FLOAT_ARR_HANDLE.get(buffer, addr);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 4);
        }
    }

    @Override
    public void writeF64(int addr, double data) {
        try {
            DOUBLE_ARR_HANDLE.set(buffer, addr, data);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 8);
        }
    }

    @Override
    public double readDouble(int addr) {
        try {
            return (double) DOUBLE_ARR_HANDLE.get(buffer, addr);
        } catch (RuntimeException e) {
            throw outOfBoundsException(e, addr, 8);
        }
    }

    @Override
    public long readF64(int addr) {
        return readLong(addr);
    }

    @Override
    public void zero() {
        Arrays.fill(buffer, (byte) 0);
    }

    @Override
    public void fill(byte value, int fromIndex, int toIndex) {
        if (outOfBounds(fromIndex, toIndex - fromIndex, buffer.length)) {
            throw new WasmRuntimeException(boundsMessage(fromIndex, toIndex - fromIndex));
        }
        Arrays.fill(buffer, fromIndex, toIndex, value);
    }

    @Override
    public void copy(int dest, int src, int size) {
        if (outOfBounds(src, size, buffer.length) || outOfBounds(dest, size, buffer.length)) {
            throw new WasmRuntimeException(boundsMessage(Math.max(src, dest), size));
        }
        System.arraycopy(buffer, src, buffer, dest, size);
    }
}
