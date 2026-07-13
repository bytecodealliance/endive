package run.endive.redline.runner.internal;

import static run.endive.runtime.ConstantEvaluators.computeConstantValue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final class WaitState {
        int waiterCount;
        int pendingWakeups;
    }

    private final Map<Integer, WaitState> waitStates = new ConcurrentHashMap<>();
    private final MemoryLimits limits;
    private final MemorySegment reserved;
    private MemorySegment segment;
    private int nPages;
    private final long reservedSize;
    private DataSegment[] dataSegments;

    public NativeMemory(MemoryLimits limits) {
        this.limits = limits;
        this.nPages = limits.initialPages();
        int maxPages = Math.min(limits.maximumPages(), RUNTIME_MAX_PAGES);
        this.reservedSize = PAGE_SIZE * (long) maxPages;

        try {
            this.reserved = PanamaExecutor.mmapNoAccess(reservedSize);

            if (nPages > 0) {
                PanamaExecutor.mprotectReadWrite(reserved, PAGE_SIZE * (long) nPages);
            }
        } catch (Throwable t) {
            throw new WasmEngineException("Failed to mmap native memory", t);
        }

        this.segment = reserved.reinterpret(PAGE_SIZE * (long) nPages);
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
    }

    /** Get the native address of the memory buffer, for passing to native code. */
    public MemorySegment nativeAddress() {
        return segment;
    }

    @Override
    public int pages() {
        return nPages;
    }

    @Override
    public int grow(int size) {
        var prevPages = nPages;
        var numPages = prevPages + size;
        if (numPages > maximumPages() || numPages < prevPages) {
            return -1;
        }

        try {
            long newSize = PAGE_SIZE * (long) numPages;
            PanamaExecutor.mprotectReadWrite(reserved, newSize);
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
        return waitStates.computeIfAbsent(address, k -> new WaitState());
    }

    private int waitOn(int address, java.util.function.BooleanSupplier condition, long timeout) {
        if (!shared()) {
            throw new WasmEngineException("Attempt to wait on a non-shared memory, not supported.");
        }

        long deadline = (timeout < 0) ? Long.MAX_VALUE : System.nanoTime() + timeout;
        WaitState state = waitStates.computeIfAbsent(address, k -> new WaitState());

        synchronized (state) {
            if (!condition.getAsBoolean()) {
                return 1; // not-equal
            }

            state.waiterCount++;
            try {
                while (state.pendingWakeups == 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return 2; // timeout
                    }
                    long millis = Math.max(remaining / 1_000_000L, 0);
                    int nanos = Math.max((int) (remaining % 1_000_000L), 0);
                    try {
                        state.wait(millis, nanos);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new WasmEngineException("Thread interrupted");
                    }
                }
                return 0; // woken
            } finally {
                if (state.pendingWakeups > 0) {
                    state.pendingWakeups--;
                }
                state.waiterCount--;
            }
        }
    }

    @SuppressWarnings("removal")
    @Override
    public int waitOn(int address, int expected, long timeout) {
        return waitOn(address, () -> readInt(address) == expected, timeout);
    }

    @SuppressWarnings("removal")
    @Override
    public int waitOn(int address, long expected, long timeout) {
        return waitOn(address, () -> readLong(address) == expected, timeout);
    }

    @SuppressWarnings("removal")
    @Override
    public int notify(int address, int maxThreads) {
        if (!shared()) {
            return 0;
        }

        WaitState state = waitStates.get(address);
        if (state == null) {
            return 0;
        }

        synchronized (state) {
            int actualWaiters = state.waiterCount - state.pendingWakeups;
            if (actualWaiters == 0) {
                return 0;
            }
            int toWake;
            if (maxThreads < 0) {
                toWake = actualWaiters;
            } else {
                toWake = Math.min(actualWaiters, maxThreads);
            }
            state.pendingWakeups += toWake;
            state.notifyAll();
            return toWake;
        }
    }

    @Override
    public void initialize(Instance instance, DataSegment[] dataSegments) {
        this.dataSegments = dataSegments;
        if (dataSegments == null) {
            return;
        }
        for (var s : dataSegments) {
            if (s instanceof ActiveDataSegment) {
                var seg = (ActiveDataSegment) s;
                var data = seg.data();
                var offset = (int) computeConstantValue(instance, seg.offsetInstructions())[0];
                if (offset < 0 || offset + data.length > sizeInBytes()) {
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
        if (seg == null || seg == run.endive.wasm.types.PassiveDataSegment.EMPTY) {
            if (size > 0) {
                throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
            }
            return;
        }
        write(dest, seg.data(), offset, size);
    }

    private int sizeInBytes() {
        return PAGE_SIZE * nPages;
    }

    @Override
    public void write(int addr, byte[] data, int offset, int size) {
        long limit = segment.byteSize();
        if (Integer.toUnsignedLong(offset) + Integer.toUnsignedLong(size) > data.length
                || Integer.toUnsignedLong(addr) + Integer.toUnsignedLong(size) > limit) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        MemorySegment.copy(MemorySegment.ofArray(data), offset, segment, addr, size);
    }

    @Override
    public byte read(int addr) {
        return segment.get(ValueLayout.JAVA_BYTE, addr);
    }

    @Override
    public byte[] readBytes(int addr, int len) {
        return segment.asSlice(addr, len).toArray(ValueLayout.JAVA_BYTE);
    }

    @Override
    public void writeI32(int addr, int data) {
        segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr, data);
    }

    @Override
    public int readInt(int addr) {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr);
    }

    @Override
    public void writeLong(int addr, long data) {
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr, data);
    }

    @Override
    public long readLong(int addr) {
        return segment.get(
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr);
    }

    @Override
    public void writeShort(int addr, short data) {
        segment.set(
                ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr, data);
    }

    @Override
    public short readShort(int addr) {
        return segment.get(
                ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), addr);
    }

    @Override
    public long readU16(int addr) {
        return readShort(addr) & 0xFFFFL;
    }

    @Override
    public void writeByte(int addr, byte data) {
        segment.set(ValueLayout.JAVA_BYTE, addr, data);
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
        long limit = segment.byteSize();
        if (Integer.toUnsignedLong(src) + Integer.toUnsignedLong(size) > limit
                || Integer.toUnsignedLong(dest) + Integer.toUnsignedLong(size) > limit) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        MemorySegment.copy(segment, src, segment, dest, size);
    }

    @Override
    public void fill(byte value, int fromIndex, int toIndex) {
        long limit = segment.byteSize();
        if (Integer.toUnsignedLong(fromIndex) > limit
                || Integer.toUnsignedLong(toIndex) > limit
                || fromIndex > toIndex) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        segment.asSlice(fromIndex, toIndex - fromIndex).fill(value);
    }

    @Override
    public void drop(int segment) {
        if (dataSegments != null) {
            dataSegments[segment] = PassiveDataSegment.EMPTY;
        }
    }
}
