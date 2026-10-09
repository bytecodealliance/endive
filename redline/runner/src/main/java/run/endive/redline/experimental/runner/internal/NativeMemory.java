package run.endive.redline.experimental.runner.internal;

import static run.endive.runtime.ConstantEvaluators.computeConstantValue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import run.endive.redline.experimental.api.internal.NativeAtomics;
import run.endive.redline.experimental.api.internal.NativeWaiters;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.ActiveDataSegment;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;

/**
 * Off-heap contiguous Memory backed by mmap/mprotect.
 *
 * <p>Reserves the full maximum address range upfront with PROT_NONE (no physical
 * memory committed), then mprotects pages as needed. Grow is zero-copy — just
 * mprotect the next range. The base address never changes.
 */
public final class NativeMemory implements Memory, AutoCloseable {

    // Static final so the JIT folds each layout's VarHandle; withOrder() allocates per call.
    private static final ValueLayout.OfInt INT_UNALIGNED_LE =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_UNALIGNED_LE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort SHORT_UNALIGNED_LE =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle PAGES_CELL = ValueLayout.JAVA_INT.varHandle();
    private static final byte[] NO_DATA = new byte[0];

    private final MemoryLimits limits;
    private final MemorySegment reserved;
    private final long reservedSize;
    private final NativeWaiters waiters;
    private final NativeAtomics atomics;
    // Page count compiled code reads for a shared memory, which other instances may grow
    private MemorySegment pagesCell;
    private volatile MemorySegment segment;
    private volatile int nPages;
    private DataSegment[] dataSegments;

    public NativeMemory(MemoryLimits limits) {
        this.limits = limits;
        int maxPages = Math.min(limits.maximumPages(), RUNTIME_MAX_PAGES);
        if (limits.initialPages() > maxPages) {
            throw new UninstantiableException(
                    "memory of "
                            + limits.initialPages()
                            + " pages exceeds the limit of "
                            + maxPages
                            + " pages");
        }
        this.nPages = limits.initialPages();
        this.reservedSize = PAGE_SIZE * (long) maxPages;
        this.waiters = new NativeWaiters(limits.shared());

        MemorySegment mapping = MemorySegment.NULL;
        try {
            mapping = PanamaExecutor.mmapNoAccess(reservedSize);
            if (nPages > 0) {
                PanamaExecutor.mprotectReadWrite(mapping, PAGE_SIZE * (long) nPages);
            }
            this.pagesCell = PanamaExecutor.malloc(Integer.BYTES);
        } catch (Throwable t) {
            var failure = new WasmEngineException("Failed to mmap native memory", t);
            if (reservedSize > 0 && mapping.address() != 0) {
                try {
                    PanamaExecutor.munmap(mapping, reservedSize);
                } catch (Throwable e) {
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        }

        this.reserved = mapping;
        PAGES_CELL.setVolatile(pagesCell, 0L, nPages);
        this.segment = reserved.reinterpret(PAGE_SIZE * (long) nPages);
        this.atomics =
                new NativeAtomics(
                        reservedSize > 0
                                ? reserved.reinterpret(reservedSize).asByteBuffer()
                                : ByteBuffer.allocateDirect(0));
    }

    @Override
    public void close() {
        if (reservedSize > 0) {
            try {
                PanamaExecutor.munmap(reserved, reservedSize);
            } catch (Throwable e) {
                throw new WasmEngineException("Failed to unmap native memory", e);
            }
        }
        if (pagesCell.address() != 0) {
            try {
                PanamaExecutor.free(pagesCell);
            } catch (Throwable e) {
                throw new WasmEngineException("Failed to free native memory page count", e);
            }
            pagesCell = MemorySegment.NULL;
        }
    }

    /** Get the native address of the memory buffer, for passing to native code. */
    public MemorySegment nativeAddress() {
        return segment;
    }

    /** Where compiled code reads the current page count of a shared memory. */
    public MemorySegment pagesAddress() {
        return pagesCell;
    }

    @Override
    public int pages() {
        return nPages;
    }

    @Override
    public synchronized int grow(int size) {
        var prevPages = nPages;
        var numPages = prevPages + size;
        if (numPages > maximumPages() || numPages < prevPages) {
            return -1;
        }

        try {
            long newSize = PAGE_SIZE * (long) numPages;
            PanamaExecutor.mprotectReadWrite(reserved, newSize);
            PAGES_CELL.setVolatile(pagesCell, 0L, numPages);
            // the segment first, so a host that sees the new page count can access it
            this.segment = reserved.reinterpret(newSize);
            this.nPages = numPages;
        } catch (Throwable t) {
            return -1;
        }

        return prevPages;
    }

    @Override
    public int initialPages() {
        return limits.initialPages();
    }

    @Override
    public int maximumPages() {
        return Math.min(limits.maximumPages(), RUNTIME_MAX_PAGES);
    }

    @Override
    public boolean shared() {
        return limits.shared();
    }

    @SuppressWarnings("removal")
    @Override
    public Object lock(int address) {
        if (!shared()) {
            return new Object();
        }
        return waiters.monitor(address);
    }

    @SuppressWarnings("removal")
    @Override
    public int waitOn(int address, int expected, long timeout) {
        return waiters.waitOn(address, () -> atomicReadInt(address) == expected, timeout);
    }

    @SuppressWarnings("removal")
    @Override
    public int waitOn(int address, long expected, long timeout) {
        return waiters.waitOn(address, () -> atomicReadLong(address) == expected, timeout);
    }

    @SuppressWarnings("removal")
    @Override
    public int notify(int address, int maxThreads) {
        return waiters.notify(address, maxThreads);
    }

    @Override
    public void initialize(Instance instance, DataSegment[] dataSegments) {
        this.dataSegments = dataSegments;
        if (dataSegments == null) {
            return;
        }
        for (var s : dataSegments) {
            if (s instanceof ActiveDataSegment seg) {
                var data = seg.bytes();
                var offset = (int) computeConstantValue(instance, seg.offsetInstructions())[0];
                if (offset < 0 || (long) offset + data.length > sizeInBytes()) {
                    throw new UninstantiableException(
                            "out of bounds memory access: offset="
                                    + offset
                                    + " size="
                                    + data.length);
                }
                MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, offset, data.length);
            }
        }
    }

    @Override
    public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
        var seg = dataSegments[segmentId];
        // a dropped segment is empty, and still bounds-checks both offsets
        var data = (seg == null || seg == PassiveDataSegment.EMPTY) ? NO_DATA : seg.bytes();
        write(dest, data, offset, size);
    }

    private long sizeInBytes() {
        return PAGE_SIZE * (long) nPages;
    }

    @Override
    public void write(int addr, byte[] data, int offset, int size) {
        var view = segment;
        if (Integer.toUnsignedLong(offset) + Integer.toUnsignedLong(size) > data.length
                || Integer.toUnsignedLong(addr) + Integer.toUnsignedLong(size) > view.byteSize()) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        MemorySegment.copy(MemorySegment.ofArray(data), offset, view, addr, size);
    }

    /**
     * A MemorySegment reports an out of bounds access its own way, but a host
     * reading past the end of a Wasm memory has to see the same trap it would
     * from any other backend.
     */
    private static run.endive.runtime.WasmRuntimeException outOfBounds(int addr) {
        return new run.endive.runtime.WasmRuntimeException(
                "out of bounds memory access: attempted to access address: " + addr);
    }

    private int checkAtomic(int addr, int size) {
        if (Integer.toUnsignedLong(addr) + size > segment.byteSize()) {
            throw outOfBounds(addr);
        }
        return addr;
    }

    @Override
    public byte read(int addr) {
        try {
            return segment.get(ValueLayout.JAVA_BYTE, addr);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public byte[] readBytes(int addr, int len) {
        var view = segment;
        if (len < 0
                || Integer.toUnsignedLong(addr) + Integer.toUnsignedLong(len) > view.byteSize()) {
            throw outOfBounds(addr);
        }
        byte[] result = new byte[len];
        MemorySegment.copy(view, ValueLayout.JAVA_BYTE, addr, result, 0, len);
        return result;
    }

    @Override
    public void writeI32(int addr, int data) {
        try {
            segment.set(INT_UNALIGNED_LE, addr, data);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public int readInt(int addr) {
        try {
            return segment.get(INT_UNALIGNED_LE, addr);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public void writeLong(int addr, long data) {
        try {
            segment.set(LONG_UNALIGNED_LE, addr, data);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public long readLong(int addr) {
        try {
            return segment.get(LONG_UNALIGNED_LE, addr);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public void writeShort(int addr, short data) {
        try {
            segment.set(SHORT_UNALIGNED_LE, addr, data);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public short readShort(int addr) {
        try {
            return segment.get(SHORT_UNALIGNED_LE, addr);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public long readU16(int addr) {
        return readShort(addr) & 0xFFFFL;
    }

    @Override
    public void writeByte(int addr, byte data) {
        try {
            segment.set(ValueLayout.JAVA_BYTE, addr, data);
        } catch (IndexOutOfBoundsException e) {
            throw outOfBounds(addr);
        }
    }

    @Override
    public void writeF32(int addr, float data) {
        writeI32(addr, Float.floatToRawIntBits(data));
    }

    @Override
    public long readF32(int addr) {
        return readInt(addr);
    }

    @Override
    public float readFloat(int addr) {
        return Float.intBitsToFloat(readInt(addr));
    }

    @Override
    public void writeF64(int addr, double data) {
        writeLong(addr, Double.doubleToRawLongBits(data));
    }

    @Override
    public double readDouble(int addr) {
        return Double.longBitsToDouble(readLong(addr));
    }

    @Override
    public long readF64(int addr) {
        return readLong(addr);
    }

    @Override
    public void zero() {
        segment.fill((byte) 0);
    }

    @Override
    public void copy(int dest, int src, int size) {
        var view = segment;
        long limit = view.byteSize();
        if (Integer.toUnsignedLong(src) + Integer.toUnsignedLong(size) > limit
                || Integer.toUnsignedLong(dest) + Integer.toUnsignedLong(size) > limit) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        MemorySegment.copy(view, src, view, dest, size);
    }

    @Override
    public void fill(byte value, int fromIndex, int toIndex) {
        var view = segment;
        long limit = view.byteSize();
        if (Integer.toUnsignedLong(fromIndex) > limit
                || Integer.toUnsignedLong(toIndex) > limit
                || fromIndex > toIndex) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        view.asSlice(fromIndex, toIndex - fromIndex).fill(value);
    }

    @Override
    public void drop(int segment) {
        if (dataSegments != null) {
            dataSegments[segment] = PassiveDataSegment.EMPTY;
        }
    }

    @Override
    public int atomicReadInt(int addr) {
        return atomics.readInt(checkAtomic(addr, 4));
    }

    @Override
    public long atomicReadLong(int addr) {
        return atomics.readLong(checkAtomic(addr, 8));
    }

    @Override
    public short atomicReadShort(int addr) {
        return atomics.readShort(checkAtomic(addr, 2));
    }

    @Override
    public byte atomicReadByte(int addr) {
        return atomics.readByte(checkAtomic(addr, 1));
    }

    @Override
    public void atomicWriteInt(int addr, int value) {
        atomics.writeInt(checkAtomic(addr, 4), value);
    }

    @Override
    public void atomicWriteLong(int addr, long value) {
        atomics.writeLong(checkAtomic(addr, 8), value);
    }

    @Override
    public void atomicWriteShort(int addr, short value) {
        atomics.writeShort(checkAtomic(addr, 2), value);
    }

    @Override
    public void atomicWriteByte(int addr, byte value) {
        atomics.writeByte(checkAtomic(addr, 1), value);
    }

    @Override
    public int atomicAddInt(int addr, int delta) {
        return atomics.addInt(checkAtomic(addr, 4), delta);
    }

    @Override
    public int atomicAndInt(int addr, int mask) {
        return atomics.andInt(checkAtomic(addr, 4), mask);
    }

    @Override
    public int atomicOrInt(int addr, int mask) {
        return atomics.orInt(checkAtomic(addr, 4), mask);
    }

    @Override
    public int atomicXorInt(int addr, int mask) {
        return atomics.xorInt(checkAtomic(addr, 4), mask);
    }

    @Override
    public int atomicXchgInt(int addr, int value) {
        return atomics.xchgInt(checkAtomic(addr, 4), value);
    }

    @Override
    public int atomicCmpxchgInt(int addr, int expected, int replacement) {
        return atomics.cmpxchgInt(checkAtomic(addr, 4), expected, replacement);
    }

    @Override
    public long atomicAddLong(int addr, long delta) {
        return atomics.addLong(checkAtomic(addr, 8), delta);
    }

    @Override
    public long atomicAndLong(int addr, long mask) {
        return atomics.andLong(checkAtomic(addr, 8), mask);
    }

    @Override
    public long atomicOrLong(int addr, long mask) {
        return atomics.orLong(checkAtomic(addr, 8), mask);
    }

    @Override
    public long atomicXorLong(int addr, long mask) {
        return atomics.xorLong(checkAtomic(addr, 8), mask);
    }

    @Override
    public long atomicXchgLong(int addr, long value) {
        return atomics.xchgLong(checkAtomic(addr, 8), value);
    }

    @Override
    public long atomicCmpxchgLong(int addr, long expected, long replacement) {
        return atomics.cmpxchgLong(checkAtomic(addr, 8), expected, replacement);
    }

    @Override
    public short atomicAddShort(int addr, short delta) {
        return atomics.addShort(checkAtomic(addr, 2), delta);
    }

    @Override
    public short atomicAndShort(int addr, short mask) {
        return atomics.andShort(checkAtomic(addr, 2), mask);
    }

    @Override
    public short atomicOrShort(int addr, short mask) {
        return atomics.orShort(checkAtomic(addr, 2), mask);
    }

    @Override
    public short atomicXorShort(int addr, short mask) {
        return atomics.xorShort(checkAtomic(addr, 2), mask);
    }

    @Override
    public short atomicXchgShort(int addr, short value) {
        return atomics.xchgShort(checkAtomic(addr, 2), value);
    }

    @Override
    public short atomicCmpxchgShort(int addr, short expected, short replacement) {
        return atomics.cmpxchgShort(checkAtomic(addr, 2), expected, replacement);
    }

    @Override
    public byte atomicAddByte(int addr, byte delta) {
        return atomics.addByte(checkAtomic(addr, 1), delta);
    }

    @Override
    public byte atomicAndByte(int addr, byte mask) {
        return atomics.andByte(checkAtomic(addr, 1), mask);
    }

    @Override
    public byte atomicOrByte(int addr, byte mask) {
        return atomics.orByte(checkAtomic(addr, 1), mask);
    }

    @Override
    public byte atomicXorByte(int addr, byte mask) {
        return atomics.xorByte(checkAtomic(addr, 1), mask);
    }

    @Override
    public byte atomicXchgByte(int addr, byte value) {
        return atomics.xchgByte(checkAtomic(addr, 1), value);
    }

    @Override
    public byte atomicCmpxchgByte(int addr, byte expected, byte replacement) {
        return atomics.cmpxchgByte(checkAtomic(addr, 1), expected, replacement);
    }
}
