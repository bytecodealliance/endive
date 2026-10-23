package run.endive.redline.experimental.runner.internal;

import static run.endive.wasm.types.Value.REF_NULL_VALUE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import run.endive.redline.experimental.api.internal.Borrowers;
import run.endive.redline.experimental.api.internal.CtxBuffer;
import run.endive.redline.experimental.api.internal.NativeResource;
import run.endive.redline.experimental.api.internal.NativeResourceCount;
import run.endive.runtime.Instance;
import run.endive.runtime.TableInstance;
import run.endive.runtime.TrapException;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;

/**
 * Off-heap table implementation for native compilation.
 *
 * <p>Layout: [size:i32 @ 0][max:i32 @ 4][entries... @ 8]
 *
 * <p>Each entry is 24 bytes:
 * <pre>
 *   [0..4)   i32  canonicalTypeIdx
 *   [4..8)   i32  funcId
 *   [8..16)  i64  funcPtr (native address)
 *   [16..24) i64  ownerId (id of the native instance defining the function)
 * </pre>
 *
 * <p>NULL entry: funcId == REF_NULL_VALUE (-1), funcPtr == 0, typeIdx == 0, ownerId == 0.
 *
 * <p>The type index, function id and pointer belong to the instance that owns
 * the function, which is not necessarily the one using the table. Native code
 * calls an entry directly only from the owner's instance. The table keeps every
 * native instance whose id its entries hold, see {@link #writers}. An entry
 * owned by an instance without native code has no pointer and no owner id; its
 * owner is kept in {@link #owners} instead.
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
public final class NativeTable extends TableInstance implements NativeResource {

    private static final int MAX_PREALLOC = 1_000_000;

    private final MemorySegment buffer;
    private final int capacity;
    private final boolean isExternRef;
    // null for a table its instance defines: that instance's arena frees it
    private final Arena ownArena;
    private final Borrowers borrowers = new Borrowers();
    private boolean closed;

    /**
     * Owner of each entry written from Java without an owner id: one not
     * yet resolved, or one owned by an instance without native code. It grows
     * with the table instead of being allocated at capacity, which can be a
     * million entries.
     */
    private Instance[] owners = new Instance[0];

    // Machines whose ids entries may hold, replaced on change so readers need no lock
    private volatile NativeMachine[] writers = new NativeMachine[0];
    private int pruneAt = 8;
    private volatile NativeMachine lastWriter;

    /** A table a module defines, allocated in its instance's arena. */
    public NativeTable(Table table, int initValue, Arena arena) {
        this(table, initValue, arena, null);
    }

    private NativeTable(Table table, int initValue, Arena arena, Arena ownArena) {
        super(table, initValue);
        this.ownArena = ownArena;
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
        // compiled table.grow checks only this, so it must not exceed the buffer
        buffer.set(
                ValueLayout.JAVA_INT,
                CtxBuffer.TABLE_MAX_OFFSET,
                max > 0 ? Math.min(max, capacity) : capacity);

        // Only the entries below size are reachable: every access bounds-checks
        // against the size field, and grow fills the slots it exposes. Filling
        // the whole capacity here would make the entire pre-allocation resident.
        for (int i = 0; i < initial; i++) {
            writeNullEntry(i);
        }

        if (initValue != REF_NULL_VALUE) {
            // Instance records the owner right after; otherwise the first caller takes it
            for (int i = 0; i < initial; i++) {
                writeUnresolvedEntry(i, initValue, null);
            }
        }
    }

    /** A table for the host to pass in as an import, freed by {@link #close()}. */
    public static NativeTable standalone(Table table, int initValue) {
        var arena = Arena.ofShared();
        var nativeTable = new NativeTable(table, initValue, arena, arena);
        NativeResourceCount.allocated();
        return nativeTable;
    }

    private long entryBase(int index) {
        return CtxBuffer.TABLE_ENTRIES_OFFSET + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
    }

    private void writeNullEntry(int index) {
        writeEntry(index, 0, REF_NULL_VALUE, 0L, 0L);
        setOwner(index, null);
    }

    /**
     * Writes a funcref with no native address, either not resolved yet or owned
     * by an instance without native code.
     *
     * @param index the entry to write
     * @param value the function id, in {@code owner}'s index space
     * @param owner the instance that defines the function, or {@code null} if
     *     unknown
     */
    private void writeUnresolvedEntry(int index, int value, Instance owner) {
        writeEntry(index, 0, value, 0L, 0L);
        setOwner(index, owner);
    }

    /**
     * Writes every field of entry {@code index}.
     *
     * @param index the entry to write
     * @param typeIdx the owner's canonical type index for the function
     * @param funcId the function id, in the owner's index space
     * @param funcPtr the function's native address, or 0
     * @param ownerId the owner's id, or 0
     */
    private void writeEntry(int index, int typeIdx, int funcId, long funcPtr, long ownerId) {
        long base = entryBase(index);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
        buffer.set(ValueLayout.JAVA_INT, base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
        buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
        buffer.set(ValueLayout.JAVA_LONG, base + CtxBuffer.ENTRY_OWNER_ID_OFFSET, ownerId);
    }

    private long ownerId(int index) {
        return buffer.get(
                ValueLayout.JAVA_LONG, entryBase(index) + CtxBuffer.ENTRY_OWNER_ID_OFFSET);
    }

    /** Resolves entries written with no owner, which only a host does, against {@code instance}. */
    void resolvePendingRefs(Instance instance) {
        if (isExternRef) {
            return;
        }
        int sz = size();
        for (int i = 0; i < sz; i++) {
            int funcId =
                    buffer.get(ValueLayout.JAVA_INT, entryBase(i) + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
            if (funcId != REF_NULL_VALUE && ownerId(i) == 0L && recordedOwner(i) == null) {
                resolveFromInstance(i, funcId, instance);
            }
        }
    }

    /**
     * Writes {@code funcId} with the native address, type index and id of
     * {@code owner}'s function.
     *
     * @param index the entry to write
     * @param funcId the function id, in {@code owner}'s index space
     * @param owner the open native machine that defines the function
     */
    private void writeResolvedEntry(int index, int funcId, NativeMachine owner) {
        if (funcId < 0 || funcId >= owner.functionCount()) {
            // a funcref from another instance can carry an id outside this one's functions
            throw new TrapException("undefined element");
        }
        long funcPtr = owner.getFuncTable().get(ValueLayout.JAVA_LONG, (long) funcId * 8);
        if (funcPtr == 0L) {
            // Not compiled, so only the owner's runner can report it
            writeUnresolvedEntry(index, funcId, owner.instance());
            return;
        }
        registerWriter(owner);
        int typeIdx = owner.getFuncTypesArray().get(ValueLayout.JAVA_INT, (long) funcId * 4);
        writeEntry(index, typeIdx, funcId, funcPtr, owner.ownerId());
        setOwner(index, null);
    }

    /**
     * Writes {@code funcId} as a function of {@code instance}. An open native
     * instance gives the entry its address and id. Otherwise the entry keeps
     * its owner here and is called through the runner.
     *
     * @param index the entry to write
     * @param funcId the function id, in {@code instance}'s index space
     * @param instance the instance that defines the function, or {@code null}
     */
    private void resolveFromInstance(int index, int funcId, Instance instance) {
        if (isExternRef) {
            writeEntry(index, 0, funcId, 0L, 0L);
        } else if (instance != null
                && instance.getMachine() instanceof NativeMachine nm
                && !nm.isClosed()) {
            writeResolvedEntry(index, funcId, nm);
        } else {
            writeUnresolvedEntry(index, funcId, instance);
        }
    }

    /**
     * Returns the owner recorded for entry {@code index}.
     *
     * @param index the entry
     * @return the recorded owner, or {@code null}
     */
    private Instance recordedOwner(int index) {
        return index < owners.length ? owners[index] : null;
    }

    /**
     * Records the owner of entry {@code index}, growing {@link #owners} as
     * needed.
     *
     * @param index the entry
     * @param owner the owning instance, or {@code null}
     */
    private void setOwner(int index, Instance owner) {
        if (index >= owners.length) {
            if (owner == null) {
                return;
            }
            ensureOwners(index + 1);
        }
        owners[index] = owner;
    }

    private void ensureOwners(int length) {
        if (length > owners.length) {
            owners = Arrays.copyOf(owners, Math.max(length, Math.max(size(), owners.length * 2)));
        }
    }

    /** Keeps {@code writer}, whose id entries of this table may now hold. */
    void registerWriter(NativeMachine writer) {
        if (lastWriter == writer) {
            return;
        }
        synchronized (this) {
            for (NativeMachine w : writers) {
                if (w == writer) {
                    lastWriter = writer;
                    return;
                }
            }
            if (writers.length >= pruneAt) {
                pruneWriters();
                pruneAt = Math.max(8, writers.length * 2);
            }
            NativeMachine[] grown = Arrays.copyOf(writers, writers.length + 1);
            grown[writers.length] = writer;
            writers = grown;
            lastWriter = writer;
        }
    }

    // Drops closed writers that no entry refers to any more
    private void pruneWriters() {
        NativeMachine[] current = writers;
        boolean[] keep = new boolean[current.length];
        boolean anyClosed = false;
        for (int w = 0; w < current.length; w++) {
            keep[w] = !current[w].isClosed();
            anyClosed |= !keep[w];
        }
        if (!anyClosed) {
            return;
        }
        int sz = size();
        for (int i = 0; i < sz; i++) {
            long id = ownerId(i);
            if (id != 0L) {
                for (int w = 0; w < current.length; w++) {
                    if (current[w].ownerId() == id) {
                        keep[w] = true;
                        break;
                    }
                }
            }
        }
        NativeMachine[] kept = new NativeMachine[current.length];
        int count = 0;
        for (int w = 0; w < current.length; w++) {
            if (keep[w]) {
                kept[count++] = current[w];
            }
        }
        writers = Arrays.copyOf(kept, count);
        lastWriter = null;
    }

    /** Returns the machine, possibly closed, with owner id {@code id}. */
    private NativeMachine writer(long id) {
        for (NativeMachine w : writers) {
            if (w.ownerId() == id) {
                return w;
            }
        }
        throw new WasmEngineException("table entry owner " + id + " is not registered");
    }

    /** Whether entries of this table may hold {@code machine}'s id. */
    boolean hasWriter(NativeMachine machine) {
        for (NativeMachine w : writers) {
            if (w == machine) {
                return true;
            }
        }
        return false;
    }

    /** Returns how many machines the table keeps for its entries. */
    int writerCount() {
        return writers.length;
    }

    /** Writes {@code funcId}, a function of {@code instance}, to entries [from, to). */
    void fill(int from, int to, int funcId, Instance instance) {
        for (int i = from; i < to; i++) {
            if (funcId == REF_NULL_VALUE) {
                writeNullEntry(i);
            } else {
                resolveFromInstance(i, funcId, instance);
            }
        }
    }

    /** Copies {@code count} entries and their owners from {@code src}, which may be this table. */
    void copyFrom(NativeTable src, int srcIndex, int dstIndex, int count) {
        MemorySegment.copy(
                src.buffer,
                src.entryBase(srcIndex),
                buffer,
                entryBase(dstIndex),
                (long) count * CtxBuffer.TABLE_ENTRY_SIZE);
        if (src != this) {
            long lastId = 0L;
            for (int i = dstIndex; i < dstIndex + count; i++) {
                long id = ownerId(i);
                if (id != 0L && id != lastId) {
                    registerWriter(src.writer(id));
                    lastId = id;
                }
            }
        }
        int fromSrc = Math.max(0, Math.min(count, src.owners.length - srcIndex));
        if (fromSrc > 0) {
            ensureOwners(dstIndex + fromSrc);
            // Read src.owners after the resize, as src may be this table
            System.arraycopy(src.owners, srcIndex, owners, dstIndex, fromSrc);
        }
        int clearTo = Math.min(dstIndex + count, owners.length);
        if (dstIndex + fromSrc < clearTo) {
            Arrays.fill(owners, dstIndex + fromSrc, clearTo, null);
        }
    }

    /** Get the native address of the table buffer, for passing to native code. */
    MemorySegment nativeBuffer() {
        return buffer;
    }

    @Override
    public Borrowers borrowers() {
        return borrowers;
    }

    /** Frees a host table; a table a module defines is freed with its instance instead. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (!borrowers.tryClose()) {
            throw borrowers.stillBorrowed("This table");
        }
        if (ownArena != null) {
            try {
                ownArena.close();
            } catch (IllegalStateException e) {
                borrowers.reopen();
                throw e;
            }
            NativeResourceCount.freed();
        }
        closed = true;
        borrowers.freed();
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
            throw new UninstantiableException("out of bounds table access");
        }
        fill(index, index + 1, value, instance);
    }

    @Override
    public int grow(int delta, int value, Instance instance) {
        int oldSize = size();
        int max = buffer.get(ValueLayout.JAVA_INT, CtxBuffer.TABLE_MAX_OFFSET);
        if (delta < 0 || delta > Math.min(max, capacity) - oldSize) {
            return -1;
        }
        int newSize = oldSize + delta;
        fill(oldSize, newSize, value, instance);
        // Update size
        buffer.set(ValueLayout.JAVA_INT, CtxBuffer.TABLE_SIZE_OFFSET, newSize);
        limits().grow(delta);
        return oldSize;
    }

    /**
     * Returns the instance whose function entry {@code index} holds, even if it
     * has since been closed.
     *
     * @param index the entry
     * @return the owning instance, or {@code null} for a null entry or one
     *     written with no owner
     */
    @Override
    public Instance instance(int index) {
        if (index < 0 || index >= size() || isExternRef || ref(index) == REF_NULL_VALUE) {
            return null;
        }
        long id = ownerId(index);
        return id != 0L ? writer(id).instance() : recordedOwner(index);
    }

    @Override
    public void reset() {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            writeNullEntry(i);
        }
    }
}
