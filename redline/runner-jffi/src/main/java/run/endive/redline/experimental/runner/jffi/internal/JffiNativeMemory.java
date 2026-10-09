package run.endive.redline.experimental.runner.jffi.internal;

import static run.endive.runtime.ConstantEvaluators.computeConstantValue;

import com.kenai.jffi.CallContext;
import com.kenai.jffi.CallingConvention;
import com.kenai.jffi.Invoker;
import com.kenai.jffi.Library;
import com.kenai.jffi.MemoryIO;
import com.kenai.jffi.PageManager;
import com.kenai.jffi.Type;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
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
 * Off-heap contiguous Memory backed by mmap/mprotect via jffi PageManager.
 *
 * <p>Reserves the full maximum address range upfront with PROT_NONE (no physical
 * memory committed), then mprotects pages as needed. Grow is zero-copy — just
 * mprotect the next range. The base address never changes.
 */
public final class JffiNativeMemory implements Memory, AutoCloseable {

    private static final MemoryIO MEM = MemoryIO.getInstance();
    private static final PageManager PM = PageManager.getInstance();
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    // Windows VirtualAlloc/VirtualFree via jffi Library+Invoker
    // jffi's PageManager always uses MEM_COMMIT|MEM_RESERVE which fails for
    // PROT_NONE reservations. We call VirtualAlloc directly with MEM_RESERVE only.
    private static final long VIRTUAL_ALLOC_ADDR;
    private static final long VIRTUAL_FREE_ADDR;
    private static final CallContext VIRTUAL_ALLOC_CTX;
    private static final CallContext VIRTUAL_FREE_CTX;
    private static final Invoker INV = Invoker.getInstance();
    private static final int MEM_COMMIT = 0x1000;
    private static final int MEM_RESERVE = 0x2000;
    private static final int MEM_RELEASE = 0x8000;
    private static final int PAGE_NOACCESS = 0x01;
    private static final int PAGE_READWRITE = 0x04;

    static {
        if (IS_WINDOWS) {
            Library kernel32 = Library.getCachedInstance("kernel32", Library.LAZY | Library.GLOBAL);
            if (kernel32 == null) {
                throw new ExceptionInInitializerError("Failed to load kernel32");
            }
            VIRTUAL_ALLOC_ADDR = kernel32.getSymbolAddress("VirtualAlloc");
            VIRTUAL_FREE_ADDR = kernel32.getSymbolAddress("VirtualFree");
            if (VIRTUAL_ALLOC_ADDR == 0 || VIRTUAL_FREE_ADDR == 0) {
                throw new ExceptionInInitializerError(
                        "VirtualAlloc/VirtualFree not found in kernel32");
            }
            // VirtualAlloc(LPVOID addr, SIZE_T size, DWORD type, DWORD protect) -> LPVOID
            VIRTUAL_ALLOC_CTX =
                    new CallContext(
                            Type.POINTER,
                            new Type[] {Type.POINTER, Type.ULONG, Type.UINT32, Type.UINT32},
                            CallingConvention.DEFAULT);
            // VirtualFree(LPVOID addr, SIZE_T size, DWORD freeType) -> BOOL
            VIRTUAL_FREE_CTX =
                    new CallContext(
                            Type.SINT32,
                            new Type[] {Type.POINTER, Type.ULONG, Type.UINT32},
                            CallingConvention.DEFAULT);
        } else {
            VIRTUAL_ALLOC_ADDR = 0;
            VIRTUAL_FREE_ADDR = 0;
            VIRTUAL_ALLOC_CTX = null;
            VIRTUAL_FREE_CTX = null;
        }
    }

    private static long winVirtualAlloc(long addr, long size, int type, int protect) {
        return INV.invokeN4(VIRTUAL_ALLOC_CTX, VIRTUAL_ALLOC_ADDR, addr, size, type, protect);
    }

    private static int winVirtualFree(long addr, long size, int freeType) {
        return (int) INV.invokeN3(VIRTUAL_FREE_CTX, VIRTUAL_FREE_ADDR, addr, size, freeType);
    }

    private static final byte[] NO_DATA = new byte[0];

    private final MemoryLimits limits;
    private final long reservedAddress;
    private final int reservedOsPages;
    private final int osPerWasm; // OS pages per Wasm page
    private final NativeWaiters waiters;
    private final NativeAtomics atomics;
    // Page count compiled code reads for a shared memory, which other instances may grow
    private long pagesCell;
    private volatile int nPages;
    private DataSegment[] dataSegments;

    public JffiNativeMemory(MemoryLimits limits) {
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
        int osPageSize = (int) PM.pageSize();
        this.osPerWasm = PAGE_SIZE / osPageSize;
        this.reservedOsPages = maxPages * osPerWasm;

        if (reservedOsPages > 0) {
            if (IS_WINDOWS) {
                long reservedSize = (long) reservedOsPages * osPageSize;
                this.reservedAddress = winVirtualAlloc(0, reservedSize, MEM_RESERVE, PAGE_NOACCESS);
                if (reservedAddress == 0) {
                    throw new WasmEngineException("Failed to reserve memory pages");
                }
                if (nPages > 0) {
                    long commitSize = (long) nPages * osPerWasm * osPageSize;
                    long committed =
                            winVirtualAlloc(
                                    reservedAddress, commitSize, MEM_COMMIT, PAGE_READWRITE);
                    if (committed == 0) {
                        winVirtualFree(reservedAddress, 0, MEM_RELEASE);
                        throw new WasmEngineException("Failed to commit initial memory pages");
                    }
                }
            } else {
                // PROT_NONE = 0 (reserve address space without committing)
                this.reservedAddress = PM.allocatePages(reservedOsPages, 0);
                if (reservedAddress == 0 || reservedAddress == -1) {
                    throw new WasmEngineException("Failed to reserve memory pages");
                }
                if (nPages > 0) {
                    try {
                        PM.protectPages(
                                reservedAddress,
                                nPages * osPerWasm,
                                PageManager.PROT_READ | PageManager.PROT_WRITE);
                    } catch (Throwable t) {
                        PM.freePages(reservedAddress, reservedOsPages);
                        throw new WasmEngineException("Failed to commit initial memory pages", t);
                    }
                }
            }
        } else {
            this.reservedAddress = 0;
        }

        this.waiters = new NativeWaiters(limits.shared());
        this.pagesCell = MEM.allocateMemory(Integer.BYTES, true);
        if (pagesCell == 0) {
            freeReservation();
            throw new WasmEngineException("Failed to allocate the memory page count");
        }
        MEM.putInt(pagesCell, nPages);
        this.atomics =
                new NativeAtomics(
                        reservedOsPages > 0
                                ? MEM.newDirectByteBuffer(
                                        reservedAddress, reservedOsPages * osPageSize)
                                : ByteBuffer.allocateDirect(0));
    }

    private void freeReservation() {
        if (reservedOsPages > 0 && reservedAddress != 0) {
            if (IS_WINDOWS) {
                winVirtualFree(reservedAddress, 0, MEM_RELEASE);
            } else {
                PM.freePages(reservedAddress, reservedOsPages);
            }
        }
    }

    @Override
    public void close() {
        freeReservation();
        if (pagesCell != 0) {
            MEM.freeMemory(pagesCell);
            pagesCell = 0;
        }
    }

    /** Get the native address of the memory buffer, for passing to native code. */
    public long nativeAddress() {
        return reservedAddress;
    }

    /** Where compiled code reads the current page count of a shared memory. */
    public long pagesAddress() {
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
            if (IS_WINDOWS) {
                int osPageSize = (int) PM.pageSize();
                long commitSize = (long) numPages * osPerWasm * osPageSize;
                if (commitSize > 0) {
                    long committed =
                            winVirtualAlloc(
                                    reservedAddress, commitSize, MEM_COMMIT, PAGE_READWRITE);
                    if (committed == 0) {
                        return -1;
                    }
                }
            } else {
                PM.protectPages(
                        reservedAddress,
                        numPages * osPerWasm,
                        PageManager.PROT_READ | PageManager.PROT_WRITE);
            }
            VarHandle.releaseFence();
            MEM.putInt(pagesCell, numPages);
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
            if (s instanceof ActiveDataSegment) {
                var seg = (ActiveDataSegment) s;
                var data = seg.bytes();
                var offset = (int) computeConstantValue(instance, seg.offsetInstructions())[0];
                if (offset < 0 || (long) offset + data.length > sizeInBytes()) {
                    throw new UninstantiableException(
                            "out of bounds memory access: offset="
                                    + offset
                                    + " size="
                                    + data.length);
                }
                MEM.putByteArray(reservedAddress + offset, data, 0, data.length);
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

    /**
     * jffi dereferences the address without checking it, so an out of bounds
     * host read would take the JVM down with a SIGSEGV rather than trap.
     */
    private void checkBounds(int addr, int size) {
        if (Integer.toUnsignedLong(addr) + Integer.toUnsignedLong(size) > sizeInBytes()) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
    }

    @Override
    public void write(int addr, byte[] data, int offset, int size) {
        long limit = sizeInBytes();
        if (Integer.toUnsignedLong(offset) + Integer.toUnsignedLong(size) > data.length
                || Integer.toUnsignedLong(addr) + Integer.toUnsignedLong(size) > limit) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        MEM.putByteArray(reservedAddress + addr, data, offset, size);
    }

    @Override
    public byte read(int addr) {
        checkBounds(addr, 1);
        return MEM.getByte(reservedAddress + addr);
    }

    @Override
    public byte[] readBytes(int addr, int len) {
        checkBounds(addr, len);
        byte[] result = new byte[len];
        MEM.getByteArray(reservedAddress + addr, result, 0, len);
        return result;
    }

    @Override
    public void writeI32(int addr, int data) {
        checkBounds(addr, 4);
        MEM.putInt(reservedAddress + addr, data);
    }

    @Override
    public int readInt(int addr) {
        checkBounds(addr, 4);
        return MEM.getInt(reservedAddress + addr);
    }

    @Override
    public void writeLong(int addr, long data) {
        checkBounds(addr, 8);
        MEM.putLong(reservedAddress + addr, data);
    }

    @Override
    public long readLong(int addr) {
        checkBounds(addr, 8);
        return MEM.getLong(reservedAddress + addr);
    }

    @Override
    public void writeShort(int addr, short data) {
        checkBounds(addr, 2);
        MEM.putShort(reservedAddress + addr, data);
    }

    @Override
    public short readShort(int addr) {
        checkBounds(addr, 2);
        return MEM.getShort(reservedAddress + addr);
    }

    @Override
    public long readU16(int addr) {
        return readShort(addr) & 0xFFFFL;
    }

    @Override
    public void writeByte(int addr, byte data) {
        checkBounds(addr, 1);
        MEM.putByte(reservedAddress + addr, data);
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
        MEM.setMemory(reservedAddress, sizeInBytes(), (byte) 0);
    }

    @Override
    public void copy(int dest, int src, int size) {
        long limit = sizeInBytes();
        if (Integer.toUnsignedLong(src) + Integer.toUnsignedLong(size) > limit
                || Integer.toUnsignedLong(dest) + Integer.toUnsignedLong(size) > limit) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        // Use a temp buffer to handle overlap correctly (memmove semantics)
        if (size > 0) {
            byte[] temp = new byte[size];
            MEM.getByteArray(reservedAddress + src, temp, 0, size);
            MEM.putByteArray(reservedAddress + dest, temp, 0, size);
        }
    }

    @Override
    public void fill(byte value, int fromIndex, int toIndex) {
        long limit = sizeInBytes();
        if (Integer.toUnsignedLong(fromIndex) > limit
                || Integer.toUnsignedLong(toIndex) > limit
                || fromIndex > toIndex) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        MEM.setMemory(reservedAddress + fromIndex, toIndex - fromIndex, value);
    }

    @Override
    public void drop(int segment) {
        if (dataSegments != null) {
            dataSegments[segment] = PassiveDataSegment.EMPTY;
        }
    }

    @Override
    public int atomicReadInt(int addr) {
        checkBounds(addr, 4);
        return atomics.readInt(addr);
    }

    @Override
    public long atomicReadLong(int addr) {
        checkBounds(addr, 8);
        return atomics.readLong(addr);
    }

    @Override
    public short atomicReadShort(int addr) {
        checkBounds(addr, 2);
        return atomics.readShort(addr);
    }

    @Override
    public byte atomicReadByte(int addr) {
        checkBounds(addr, 1);
        return atomics.readByte(addr);
    }

    @Override
    public void atomicWriteInt(int addr, int value) {
        checkBounds(addr, 4);
        atomics.writeInt(addr, value);
    }

    @Override
    public void atomicWriteLong(int addr, long value) {
        checkBounds(addr, 8);
        atomics.writeLong(addr, value);
    }

    @Override
    public void atomicWriteShort(int addr, short value) {
        checkBounds(addr, 2);
        atomics.writeShort(addr, value);
    }

    @Override
    public void atomicWriteByte(int addr, byte value) {
        checkBounds(addr, 1);
        atomics.writeByte(addr, value);
    }

    @Override
    public int atomicAddInt(int addr, int delta) {
        checkBounds(addr, 4);
        return atomics.addInt(addr, delta);
    }

    @Override
    public int atomicAndInt(int addr, int mask) {
        checkBounds(addr, 4);
        return atomics.andInt(addr, mask);
    }

    @Override
    public int atomicOrInt(int addr, int mask) {
        checkBounds(addr, 4);
        return atomics.orInt(addr, mask);
    }

    @Override
    public int atomicXorInt(int addr, int mask) {
        checkBounds(addr, 4);
        return atomics.xorInt(addr, mask);
    }

    @Override
    public int atomicXchgInt(int addr, int value) {
        checkBounds(addr, 4);
        return atomics.xchgInt(addr, value);
    }

    @Override
    public int atomicCmpxchgInt(int addr, int expected, int replacement) {
        checkBounds(addr, 4);
        return atomics.cmpxchgInt(addr, expected, replacement);
    }

    @Override
    public long atomicAddLong(int addr, long delta) {
        checkBounds(addr, 8);
        return atomics.addLong(addr, delta);
    }

    @Override
    public long atomicAndLong(int addr, long mask) {
        checkBounds(addr, 8);
        return atomics.andLong(addr, mask);
    }

    @Override
    public long atomicOrLong(int addr, long mask) {
        checkBounds(addr, 8);
        return atomics.orLong(addr, mask);
    }

    @Override
    public long atomicXorLong(int addr, long mask) {
        checkBounds(addr, 8);
        return atomics.xorLong(addr, mask);
    }

    @Override
    public long atomicXchgLong(int addr, long value) {
        checkBounds(addr, 8);
        return atomics.xchgLong(addr, value);
    }

    @Override
    public long atomicCmpxchgLong(int addr, long expected, long replacement) {
        checkBounds(addr, 8);
        return atomics.cmpxchgLong(addr, expected, replacement);
    }

    @Override
    public short atomicAddShort(int addr, short delta) {
        checkBounds(addr, 2);
        return atomics.addShort(addr, delta);
    }

    @Override
    public short atomicAndShort(int addr, short mask) {
        checkBounds(addr, 2);
        return atomics.andShort(addr, mask);
    }

    @Override
    public short atomicOrShort(int addr, short mask) {
        checkBounds(addr, 2);
        return atomics.orShort(addr, mask);
    }

    @Override
    public short atomicXorShort(int addr, short mask) {
        checkBounds(addr, 2);
        return atomics.xorShort(addr, mask);
    }

    @Override
    public short atomicXchgShort(int addr, short value) {
        checkBounds(addr, 2);
        return atomics.xchgShort(addr, value);
    }

    @Override
    public short atomicCmpxchgShort(int addr, short expected, short replacement) {
        checkBounds(addr, 2);
        return atomics.cmpxchgShort(addr, expected, replacement);
    }

    @Override
    public byte atomicAddByte(int addr, byte delta) {
        checkBounds(addr, 1);
        return atomics.addByte(addr, delta);
    }

    @Override
    public byte atomicAndByte(int addr, byte mask) {
        checkBounds(addr, 1);
        return atomics.andByte(addr, mask);
    }

    @Override
    public byte atomicOrByte(int addr, byte mask) {
        checkBounds(addr, 1);
        return atomics.orByte(addr, mask);
    }

    @Override
    public byte atomicXorByte(int addr, byte mask) {
        checkBounds(addr, 1);
        return atomics.xorByte(addr, mask);
    }

    @Override
    public byte atomicXchgByte(int addr, byte value) {
        checkBounds(addr, 1);
        return atomics.xchgByte(addr, value);
    }

    @Override
    public byte atomicCmpxchgByte(int addr, byte expected, byte replacement) {
        checkBounds(addr, 1);
        return atomics.cmpxchgByte(addr, expected, replacement);
    }
}
