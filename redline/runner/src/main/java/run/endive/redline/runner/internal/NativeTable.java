package run.endive.redline.runner.internal;

import static run.endive.wasm.types.Value.REF_NULL_VALUE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import run.endive.redline.api.internal.CtxBuffer;
import run.endive.runtime.Instance;
import run.endive.runtime.TableInstance;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;

/**
 * Off-heap table implementation for native compilation.
 *
 * <p>Layout: [size:i32 @ 0][max:i32 @ 4][entries... @ 8]
 *
 * <p>Each entry is 16 bytes:
 * <pre>
 *   [0..4)   i32  canonicalTypeIdx
 *   [4..8)   i32  funcId
 *   [8..16)  i64  funcPtr (native address)
 * </pre>
 *
 * <p>NULL entry: funcId == REF_NULL_VALUE (-1), funcPtr == 0, typeIdx == 0.
 *
 * <p>The entries array is pre-allocated to the table's max capacity so that
 * {@code TABLE.GROW} only bumps the size field without reallocation.
 * Native code reads/writes this buffer directly — no Java trampoline
 * needed for GET/SET/SIZE/GROW/FILL/COPY.
 *
 * <p>Resolution of funcId → funcPtr+typeIdx uses the {@code instance} parameter
 * passed to {@link #setRef}, obtaining the calling module's NativeMachine.
 * This ensures cross-module shared tables resolve to the correct native addresses.
 */
public final class NativeTable extends TableInstance {

    private static final int MAX_PREALLOC = 1_000_000;

    private final MemorySegment buffer;
    private final int capacity;
    private final boolean isExternRef;

    public NativeTable(Table table, Arena arena) {
        super(table, REF_NULL_VALUE);
        this.isExternRef = table.elementType().equals(ValType.ExternRef);
        int initial = (int) table.limits().min();
        int max = (int) table.limits().max();
        // Pre-allocate to max, capped at MAX_PREALLOC
        this.capacity = (max > 0 && max <= MAX_PREALLOC) ? max : Math.max(initial, MAX_PREALLOC);
        long bufferSize =
                CtxBuffer.TABLE_ENTRIES_OFFSET + (long) capacity * CtxBuffer.TABLE_ENTRY_SIZE;
        this.buffer = arena.allocate(bufferSize, 8);

        // Write header
        buffer.set(ValueLayout.JAVA_INT, CtxBuffer.TABLE_SIZE_OFFSET, initial);
        buffer.set(ValueLayout.JAVA_INT, CtxBuffer.TABLE_MAX_OFFSET, max > 0 ? max : capacity);

        // Fill all entries with null (funcId=-1, funcPtr=0, typeIdx=0)
        for (int i = 0; i < capacity; i++) {
            writeNullEntry(i);
        }
    }

    private long entryBase(int index) {
        return CtxBuffer.TABLE_ENTRIES_OFFSET + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
    }

    private void writeNullEntry(int index) {
        long base = entryBase(index);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, REF_NULL_VALUE);
        buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
    }

    private void writeOpaqueEntry(int index, int value) {
        long base = entryBase(index);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, value);
        buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
    }

    private void writeResolvedEntry(int index, int funcId, MemorySegment ft, MemorySegment fta) {
        long base = entryBase(index);
        long funcPtr = ft.get(ValueLayout.JAVA_LONG, (long) funcId * 8);
        int typeIdx = fta.get(ValueLayout.JAVA_INT, (long) funcId * 4);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
        buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
    }

    /**
     * Try to resolve funcId → funcPtr+typeIdx using the instance's NativeMachine.
     * For externref tables, stores the value as-is without function resolution.
     * Returns true if resolved, false if not (instance is null or not NativeMachine).
     */
    private boolean resolveFromInstance(int index, int funcId, Instance instance) {
        if (isExternRef) {
            writeOpaqueEntry(index, funcId);
            return true;
        }
        if (instance != null && instance.getMachine() instanceof NativeMachine nm) {
            writeResolvedEntry(index, funcId, nm.getFuncTable(), nm.getFuncTypesArray());
            return true;
        }
        return false;
    }

    /** Get the native address of the table buffer, for passing to native code. */
    MemorySegment nativeBuffer() {
        return buffer;
    }

    boolean isExternRef() {
        return isExternRef;
    }

    @Override
    public int size() {
        return buffer.get(ValueLayout.JAVA_INT, CtxBuffer.TABLE_SIZE_OFFSET);
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
        if (index < 0 || index >= size()) {
            throw new WasmEngineException("undefined element");
        }
        long base = entryBase(index);
        return buffer.get(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
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
        if (index < 0 || index >= size()) {
            throw new WasmEngineException("out of bounds table access");
        }
        if (value == REF_NULL_VALUE) {
            writeNullEntry(index);
        } else if (resolveFromInstance(index, value, instance)) {
            // Resolved using the calling module's NativeMachine
        } else {
            // No NativeMachine available — store funcId only (externref or non-native)
            long base = entryBase(index);
            buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, value);
            buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        }
    }

    @Override
    public int grow(int delta, int value, Instance instance) {
        int oldSize = size();
        int newSize = oldSize + delta;
        int max = buffer.get(ValueLayout.JAVA_INT, CtxBuffer.TABLE_MAX_OFFSET);
        if (delta < 0 || newSize > max || newSize > capacity) {
            return -1;
        }
        // Fill new slots
        for (int i = oldSize; i < newSize; i++) {
            if (value == REF_NULL_VALUE) {
                writeNullEntry(i);
            } else if (!resolveFromInstance(i, value, instance)) {
                long base = entryBase(i);
                buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
                buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, value);
                buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
            }
        }
        // Update size
        buffer.set(ValueLayout.JAVA_INT, CtxBuffer.TABLE_SIZE_OFFSET, newSize);
        limits().grow(delta);
        return oldSize;
    }

    @Override
    public Instance instance(int index) {
        // Single-module assumption — all entries belong to the same instance
        return null;
    }

    @Override
    public void reset() {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            writeNullEntry(i);
        }
    }
}
