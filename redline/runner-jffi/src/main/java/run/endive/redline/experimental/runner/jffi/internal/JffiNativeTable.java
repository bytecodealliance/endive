package run.endive.redline.experimental.runner.jffi.internal;

import static run.endive.wasm.types.Value.REF_NULL_VALUE;

import com.kenai.jffi.MemoryIO;
import run.endive.redline.experimental.api.internal.Borrowers;
import run.endive.redline.experimental.api.internal.CtxBuffer;
import run.endive.redline.experimental.api.internal.NativeResource;
import run.endive.redline.experimental.api.internal.NativeResourceCount;
import run.endive.runtime.Instance;
import run.endive.runtime.TableInstance;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;

/**
 * Off-heap table implementation for native compilation via jffi.
 *
 * <p>Layout: [size:i32 @ 0][max:i32 @ 4][entries... @ 8]
 *
 * <p>Each entry is 16 bytes:
 * <pre>
 *   [0..4)   i32  canonicalTypeIdx
 *   [4..8)   i32  funcId
 *   [8..16)  i64  funcPtr (native address)
 * </pre>
 */
public final class JffiNativeTable extends TableInstance implements NativeResource {

    private static final int MAX_PREALLOC = 1_000_000;
    private static final MemoryIO MEM = MemoryIO.getInstance();

    private final long bufferAddress;
    private final int capacity;
    private final boolean isExternRef;
    private final Borrowers borrowers = new Borrowers();
    private volatile boolean freed;

    public JffiNativeTable(Table table, int initValue) {
        super(table, initValue);
        this.isExternRef = table.elementType().equals(ValType.ExternRef);
        int initial = (int) table.limits().min();
        int max = (int) table.limits().max();
        this.capacity = (max > 0 && max <= MAX_PREALLOC) ? max : Math.max(initial, MAX_PREALLOC);
        long bufferSize =
                CtxBuffer.TABLE_ENTRIES_OFFSET + (long) capacity * CtxBuffer.TABLE_ENTRY_SIZE;
        this.bufferAddress = MEM.allocateMemory(bufferSize, true);
        NativeResourceCount.allocated();

        // Write header
        MEM.putInt(bufferAddress + CtxBuffer.TABLE_SIZE_OFFSET, initial);
        MEM.putInt(bufferAddress + CtxBuffer.TABLE_MAX_OFFSET, max > 0 ? max : capacity);

        // Only the entries below size are reachable: every access bounds-checks
        // against the size field, and grow fills the slots it exposes. Filling
        // the whole capacity here would make the entire pre-allocation resident.
        for (int i = 0; i < initial; i++) {
            writeNullEntry(i);
        }

        if (initValue != REF_NULL_VALUE) {
            // The instance has no machine yet, so funcPtr cannot be resolved
            // here. resolvePendingRefs fills it in once there is one.
            for (int i = 0; i < initial; i++) {
                writeUnresolvedEntry(i, initValue);
            }
        }
    }

    /** A funcref whose native address is not known yet. */
    private void writeUnresolvedEntry(int index, int value) {
        long base = bufferAddress + entryBase(index);
        MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
        MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, value);
        MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
    }

    /**
     * Fills in the native address of entries written before the instance had a
     * machine, which is the case for a table initialiser.
     */
    void resolvePendingRefs(Instance instance) {
        checkOpen();
        if (isExternRef) {
            return;
        }
        int sz = size();
        for (int i = 0; i < sz; i++) {
            long base = bufferAddress + entryBase(i);
            int funcId = MEM.getInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
            long funcPtr = MEM.getLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET);
            if (funcId != REF_NULL_VALUE && funcPtr == 0L) {
                resolveFromInstance(i, funcId, instance);
            }
        }
    }

    private long entryBase(int index) {
        return CtxBuffer.TABLE_ENTRIES_OFFSET + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
    }

    private void writeNullEntry(int index) {
        long base = bufferAddress + entryBase(index);
        MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
        MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, REF_NULL_VALUE);
        MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
    }

    private void writeOpaqueEntry(int index, int value) {
        long base = bufferAddress + entryBase(index);
        MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
        MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, value);
        MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
    }

    private void writeResolvedEntry(int index, int funcId, long ftAddr, long ftaAddr) {
        long base = bufferAddress + entryBase(index);
        long funcPtr = MEM.getLong(ftAddr + (long) funcId * 8);
        int typeIdx = MEM.getInt(ftaAddr + (long) funcId * 4);
        MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
        MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
        MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
    }

    private boolean resolveFromInstance(int index, int funcId, Instance instance) {
        if (isExternRef) {
            writeOpaqueEntry(index, funcId);
            return true;
        }
        if (instance != null && instance.getMachine() instanceof JffiNativeMachine) {
            JffiNativeMachine nm = (JffiNativeMachine) instance.getMachine();
            writeResolvedEntry(
                    index, funcId, nm.getFuncTableAddress(), nm.getFuncTypesArrayAddress());
            return true;
        }
        return false;
    }

    /** Get the native address of the table buffer, for passing to native code. */
    long nativeBufferAddress() {
        return bufferAddress;
    }

    boolean isExternRef() {
        return isExternRef;
    }

    /** Whether the off-heap buffer has been released. */
    boolean isFreed() {
        return freed;
    }

    @Override
    public Borrowers borrowers() {
        return borrowers;
    }

    @Override
    public synchronized void close() {
        if (freed) {
            return;
        }
        if (!borrowers.tryClose()) {
            throw borrowers.stillBorrowed("This table");
        }
        freed = true;
        MEM.freeMemory(bufferAddress);
        borrowers.freed();
        NativeResourceCount.freed();
    }

    // the buffer is a raw address, so reaching it after close would read freed memory
    private void checkOpen() {
        if (freed) {
            throw new IllegalStateException("This table has been closed");
        }
    }

    @Override
    public int size() {
        checkOpen();
        return MEM.getInt(bufferAddress + CtxBuffer.TABLE_SIZE_OFFSET);
    }

    @Override
    public ValType elementType() {
        return super.elementType();
    }

    @Override
    public TableLimits limits() {
        return super.limits();
    }

    @Override
    public int ref(int index) {
        checkOpen();
        if (index < 0 || index >= size()) {
            throw new WasmEngineException("undefined element");
        }
        long base = bufferAddress + entryBase(index);
        return MEM.getInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
    }

    @Override
    public int requiredRef(int index) {
        int r = ref(index);
        if (r == REF_NULL_VALUE) {
            throw new WasmEngineException("uninitialized element " + index);
        }
        return r;
    }

    @Override
    public void setRef(int index, int value, Instance instance) {
        checkOpen();
        if (index < 0 || index >= size()) {
            throw new run.endive.wasm.UninstantiableException("out of bounds table access");
        }
        if (value == REF_NULL_VALUE) {
            writeNullEntry(index);
        } else if (!resolveFromInstance(index, value, instance)) {
            writeUnresolvedEntry(index, value);
        }
    }

    @Override
    public int grow(int delta, int value, Instance instance) {
        checkOpen();
        int oldSize = size();
        int newSize = oldSize + delta;
        int max = MEM.getInt(bufferAddress + CtxBuffer.TABLE_MAX_OFFSET);
        if (delta < 0 || newSize > max || newSize > capacity) {
            return -1;
        }
        // Fill new slots
        for (int i = oldSize; i < newSize; i++) {
            if (value == REF_NULL_VALUE) {
                writeNullEntry(i);
            } else if (!resolveFromInstance(i, value, instance)) {
                writeUnresolvedEntry(i, value);
            }
        }
        // Update size
        MEM.putInt(bufferAddress + CtxBuffer.TABLE_SIZE_OFFSET, newSize);
        limits().grow(delta);
        return oldSize;
    }

    @Override
    public Instance instance(int index) {
        return null;
    }

    @Override
    public void reset() {
        checkOpen();
        int sz = size();
        for (int i = 0; i < sz; i++) {
            writeNullEntry(i);
        }
    }
}
