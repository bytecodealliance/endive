package run.endive.redline.experimental.runner.jffi.internal;

import static run.endive.runtime.ConstantEvaluators.computeConstantValue;

import com.kenai.jffi.CallContext;
import com.kenai.jffi.CallingConvention;
import com.kenai.jffi.Invoker;
import com.kenai.jffi.Library;
import com.kenai.jffi.MemoryIO;
import com.kenai.jffi.PageManager;
import com.kenai.jffi.Type;
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

    private static final class WaitState {
        int waiterCount;
        int pendingWakeups;
    }

    private final Map<Integer, WaitState> waitStates = new ConcurrentHashMap<>();
    private final MemoryLimits limits;
    private final long reservedAddress;
    private final int reservedOsPages;
    private final int osPerWasm; // OS pages per Wasm page
    private int nPages;
    private DataSegment[] dataSegments;

    public JffiNativeMemory(MemoryLimits limits) {
        this.limits = limits;
        this.nPages = limits.initialPages();
        int maxPages = Math.min(limits.maximumPages(), RUNTIME_MAX_PAGES);
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
                    PM.protectPages(
                            reservedAddress,
                            nPages * osPerWasm,
                            PageManager.PROT_READ | PageManager.PROT_WRITE);
                }
            }
        } else {
            this.reservedAddress = 0;
        }
    }

    @Override
    public void close() {
        if (reservedOsPages > 0 && reservedAddress != 0) {
            if (IS_WINDOWS) {
                winVirtualFree(reservedAddress, 0, MEM_RELEASE);
            } else {
                PM.freePages(reservedAddress, reservedOsPages);
            }
        }
    }

    /** Get the native address of the memory buffer, for passing to native code. */
    public long nativeAddress() {
        return reservedAddress;
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
                MEM.putByteArray(reservedAddress + offset, data, 0, data.length);
            }
        }
    }

    @Override
    public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
        var seg = dataSegments[segmentId];
        if (seg == null || seg == PassiveDataSegment.EMPTY) {
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
        long limit = sizeInBytes();
        if (Integer.toUnsignedLong(offset) + Integer.toUnsignedLong(size) > data.length
                || Integer.toUnsignedLong(addr) + Integer.toUnsignedLong(size) > limit) {
            throw new run.endive.runtime.WasmRuntimeException("out of bounds memory access");
        }
        MEM.putByteArray(reservedAddress + addr, data, offset, size);
    }

    @Override
    public byte read(int addr) {
        return MEM.getByte(reservedAddress + addr);
    }

    @Override
    public byte[] readBytes(int addr, int len) {
        byte[] result = new byte[len];
        MEM.getByteArray(reservedAddress + addr, result, 0, len);
        return result;
    }

    @Override
    public void writeI32(int addr, int data) {
        MEM.putInt(reservedAddress + addr, data);
    }

    @Override
    public int readInt(int addr) {
        return MEM.getInt(reservedAddress + addr);
    }

    @Override
    public void writeLong(int addr, long data) {
        MEM.putLong(reservedAddress + addr, data);
    }

    @Override
    public long readLong(int addr) {
        return MEM.getLong(reservedAddress + addr);
    }

    @Override
    public void writeShort(int addr, short data) {
        MEM.putShort(reservedAddress + addr, data);
    }

    @Override
    public short readShort(int addr) {
        return MEM.getShort(reservedAddress + addr);
    }

    @Override
    public long readU16(int addr) {
        return readShort(addr) & 0xFFFFL;
    }

    @Override
    public void writeByte(int addr, byte data) {
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
}
