package run.endive.redline.experimental.runner.jffi.internal;

import com.kenai.jffi.CallContext;
import com.kenai.jffi.CallingConvention;
import com.kenai.jffi.Closure;
import com.kenai.jffi.ClosureManager;
import com.kenai.jffi.Function;
import com.kenai.jffi.HeapInvocationBuffer;
import com.kenai.jffi.Invoker;
import com.kenai.jffi.Library;
import com.kenai.jffi.MemoryIO;
import com.kenai.jffi.PageManager;
import com.kenai.jffi.Type;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.internal.Borrowers;
import run.endive.redline.experimental.api.internal.CtxBuffer;
import run.endive.redline.experimental.api.internal.InterruptWatchdog;
import run.endive.redline.experimental.api.internal.NativeCodeCheck;
import run.endive.redline.experimental.api.internal.NativeResource;
import run.endive.redline.experimental.api.internal.NativeResourceCount;
import run.endive.redline.experimental.api.internal.TypeMapUtils;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.runtime.TrapException;
import run.endive.runtime.WasmInterruptedException;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/**
 * Machine implementation that compiles Wasm functions to native code
 * via Cranelift and executes them through jffi.
 *
 * <p>Calling convention for all compiled functions:
 * <pre>
 *   param 0: memBase  (i64/ADDRESS) — pointer to linear memory
 *   param 1: ctxPtr   (i64/ADDRESS) — pointer to call context struct
 *   param 2+: Wasm function parameters
 *   return: Wasm return value
 * </pre>
 */
public final class JffiNativeMachine implements Machine {

    private static final int CTX_SIZE = CtxBuffer.CTX_SIZE;
    private static final MemoryIO MEM = MemoryIO.getInstance();
    private static final MemoryIO CHECKED_MEM = MemoryIO.getCheckedInstance();
    private static final Invoker INVOKER = Invoker.getInstance();
    private static final PageManager PM = PageManager.getInstance();

    static final long MEMMOVE_ADDR;
    static final long MEMSET_ADDR;

    static {
        long memmove = 0;
        long memset = 0;
        Library defaultLib = Library.getCachedInstance(null, Library.LAZY | Library.GLOBAL);
        if (defaultLib != null) {
            memmove = defaultLib.getSymbolAddress("memmove");
            memset = defaultLib.getSymbolAddress("memset");
        }
        if (memmove == 0 || memset == 0) {
            // Windows: memmove/memset live in msvcrt or ucrtbase, not the default library
            for (String lib : new String[] {"msvcrt", "ucrtbase"}) {
                Library crt = Library.getCachedInstance(lib, Library.LAZY | Library.GLOBAL);
                if (crt != null) {
                    if (memmove == 0) {
                        memmove = crt.getSymbolAddress("memmove");
                    }
                    if (memset == 0) {
                        memset = crt.getSymbolAddress("memset");
                    }
                    if (memmove != 0 && memset != 0) {
                        break;
                    }
                }
            }
        }
        if (memmove == 0 || memset == 0) {
            throw new ExceptionInInitializerError("memmove/memset not found in native library");
        }
        MEMMOVE_ADDR = memmove;
        MEMSET_ADDR = memset;
    }

    // Never reused, unlike a context address, so a stale entry cannot match a new instance
    private static final AtomicLong OWNER_IDS = new AtomicLong();

    private final Instance instance;
    private final Function[] entryTrampolines; // entry trampoline per func
    private final FunctionType[] funcTypes; // wasm FunctionType per func
    private final long codeRegionAddr;
    private final int codeRegionOsPages;
    private final long ctxBufferAddr;
    private final long funcTableAddr;
    private final long funcTableSize; // byte size
    private final long argsBufferAddr;
    private final long globalsBufferAddr;
    private final long funcTypesArrayAddr;
    private final long funcTypesArraySize; // byte size
    private final long ownerId = OWNER_IDS.incrementAndGet();
    private long tablePtrsArrayAddr;
    private long tableWritersAddr;
    private JffiNativeTable[] nativeTables;
    private boolean tablesInitialized;
    private boolean ownsMemory;
    private boolean closed;
    private final int numImports;
    private final int importGlobalCount;
    private final int globalCount;
    private int definedGlobalsBound;
    private boolean importGlobalsInitialized;
    private long cachedMemBase;
    private boolean memBaseInitialized;
    private JffiNativeMemory nativeMemory;
    private volatile Throwable pendingException;
    // the machine whose outermost call is running on this thread
    private static final ThreadLocal<JffiNativeMachine> RUNNING = new ThreadLocal<>();
    // calls under way on any thread, which close() refuses to run under
    private final AtomicInteger callDepth = new AtomicInteger();
    // set by close() before it looks at callDepth, and kept once it succeeds
    private volatile boolean closing;
    private final InterruptWatchdog.InterruptSink interruptFlag = this::raiseInterruptFlag;

    // Keep closure handles alive to prevent GC
    private final Closure.Handle trampolineHandle;
    private final Closure.Handle memGrowHandle;
    private final Closure.Handle[] importHandles;

    // native frees, run newest first at close
    private final ArrayDeque<Runnable> frees;
    private final List<NativeResource> borrowed = new ArrayList<>();
    private final List<JffiNativeGlobalInstance> boundGlobals = new ArrayList<>();

    /** Builds the machine, freeing what it allocated if that fails. */
    public static JffiNativeMachine create(Instance instance, NativeCode precompiledCode) {
        var frees = new ArrayDeque<Runnable>();
        try {
            return new JffiNativeMachine(instance, precompiledCode, frees);
        } catch (RuntimeException | Error e) {
            var failure = runFrees(frees, null);
            if (failure != null) {
                e.addSuppressed(failure);
            }
            throw e;
        }
    }

    private JffiNativeMachine(
            Instance instance, NativeCode precompiledCode, ArrayDeque<Runnable> frees) {
        this.frees = frees;
        this.instance = instance;
        var module = instance.module();
        NativeCode code = NativeCodeCheck.check(precompiledCode, module);
        this.numImports =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == run.endive.wasm.types.ExternalType
                                                                .FUNCTION)
                                .count();
        int totalFuncs = numImports + module.codeSection().functionBodyCount();
        this.entryTrampolines = new Function[totalFuncs];
        this.funcTypes = new FunctionType[totalFuncs];
        this.importHandles = new Closure.Handle[numImports];

        // Allocate call context buffer
        ctxBufferAddr = allocate(CTX_SIZE);

        // Allocate function pointer table (one i64 per function)
        this.funcTableSize = (long) totalFuncs * 8;
        funcTableAddr = allocate(funcTableSize);

        // One slot per global, imported or defined, which bind() moves them onto
        this.importGlobalCount =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == run.endive.wasm.types.ExternalType
                                                                .GLOBAL)
                                .count();
        this.globalCount =
                importGlobalCount
                        + (module.globalSection() != null
                                ? module.globalSection().globalCount()
                                : 0);
        this.globalsBufferAddr = globalCount > 0 ? allocate((long) globalCount * 8) : 0L;

        // Allocate args buffer
        this.argsBufferAddr = allocate((long) CtxBuffer.ARGS_BUFFER_CAPACITY * 8);

        // Allocate funcTypes array with canonical type indices
        int[] canonicalTypeMap = TypeMapUtils.buildCanonicalTypeMap(module);
        this.funcTypesArraySize = (long) totalFuncs * 4;
        this.funcTypesArrayAddr = allocate(funcTypesArraySize);
        for (int i = 0; i < numImports; i++) {
            int rawType = instance.functionType(i);
            MEM.putInt(funcTypesArrayAddr + (long) i * 4, canonicalTypeMap[rawType]);
        }
        for (int i = 0; i < module.functionSection().functionCount(); i++) {
            int funcId = numImports + i;
            int rawType = module.functionSection().getFunctionType(i);
            MEM.putInt(funcTypesArrayAddr + (long) funcId * 4, canonicalTypeMap[rawType]);
        }

        // Tables: populated lazily
        this.nativeTables = null;
        this.tablePtrsArrayAddr = 0;
        this.tablesInitialized = false;

        // Host stubs are called by compiled code with the platform ABI
        this.trampolineHandle = track(createTrampolineStub());
        this.memGrowHandle = track(createMemGrowStub());
        for (int funcId = 0; funcId < numImports; funcId++) {
            var funcType = instance.imports().function(funcId).functionType();
            funcTypes[funcId] = funcType;
            importHandles[funcId] = track(createImportStub(funcId, funcType));
        }

        // Write pointers to ctxBuffer
        MEM.putLong(ctxBufferAddr + CtxBuffer.FUNC_TABLE_PTR, funcTableAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.TRAMPOLINE_PTR, trampolineHandle.getAddress());
        MEM.putLong(ctxBufferAddr + CtxBuffer.ARGS_PTR, argsBufferAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.GLOBALS_PTR, globalsBufferAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.MEM_GROW_PTR, memGrowHandle.getAddress());
        MEM.putLong(ctxBufferAddr + CtxBuffer.TABLE_PTRS, 0L);
        MEM.putLong(ctxBufferAddr + CtxBuffer.FUNC_TYPES_PTR, funcTypesArrayAddr);
        MEM.putLong(ctxBufferAddr + CtxBuffer.MEMMOVE_PTR, MEMMOVE_ADDR);
        MEM.putLong(ctxBufferAddr + CtxBuffer.MEMSET_PTR, MEMSET_ADDR);
        MEM.putLong(ctxBufferAddr + CtxBuffer.OWNER_ID, ownerId);

        byte[] image = code.image();

        // Allocate executable code region via PageManager
        int osPageSize = (int) PM.pageSize();
        this.codeRegionOsPages = (Math.max(image.length, 1) + osPageSize - 1) / osPageSize;
        this.codeRegionAddr =
                PM.allocatePages(codeRegionOsPages, PageManager.PROT_READ | PageManager.PROT_WRITE);
        if (codeRegionAddr == 0 || codeRegionAddr == -1) {
            throw new WasmEngineException("Failed to allocate executable code pages");
        }
        long codeAddr = codeRegionAddr;
        int codePages = codeRegionOsPages;
        frees.push(() -> PM.freePages(codeAddr, codePages));

        try {
            MEM.putByteArray(codeRegionAddr, image, 0, image.length);
            for (int funcId = 0; funcId < numImports; funcId++) {
                // the slot may be unaligned
                byte[] stubAddress =
                        ByteBuffer.allocate(8)
                                .order(ByteOrder.nativeOrder())
                                .putLong(importHandles[funcId].getAddress())
                                .array();
                MEM.putByteArray(
                        codeRegionAddr + code.importStubSlotOffset(funcId), stubAddress, 0, 8);
            }
            PM.protectPages(
                    codeRegionAddr,
                    codeRegionOsPages,
                    PageManager.PROT_READ | PageManager.PROT_EXEC);

            // Compiled code reaches imports through their trampolines
            for (int funcId = 0; funcId < numImports; funcId++) {
                MEM.putLong(
                        funcTableAddr + (long) funcId * 8,
                        codeRegionAddr + code.importTrampolineOffset(funcId));
            }

            for (int i = 0; i < code.functionBodyCount(); i++) {
                if (code.isCompiled(i)) {
                    int funcId = numImports + i;
                    var funcType =
                            (FunctionType)
                                    module.typeSection()
                                            .getType(module.functionSection().getFunctionType(i));
                    funcTypes[funcId] = funcType;
                    MEM.putLong(
                            funcTableAddr + (long) funcId * 8, codeRegionAddr + code.bodyOffset(i));
                    entryTrampolines[funcId] =
                            new Function(
                                    codeRegionAddr + code.entryTrampolineOffset(i),
                                    createEntryTrampolineCallContext(funcType));
                }
            }
        } catch (WasmEngineException e) {
            throw e;
        } catch (Throwable e) {
            throw new WasmEngineException("Failed to set up native code", e);
        }

        this.nativeMemory =
                instance.memory() instanceof JffiNativeMemory
                        ? (JffiNativeMemory) instance.memory()
                        : null;
        // An imported memory outlives this instance and may back others, so only
        // a memory this module defines is ours to close.
        this.ownsMemory = instance.imports().memoryCount() == 0;
        // last, so nothing after it can fail and leave an owner waiting on us
        borrowImports();
        NativeResourceCount.allocated();
    }

    private long allocate(long size) {
        long address = MEM.allocateMemory(size, true);
        if (address == 0) {
            throw new OutOfMemoryError("Failed to allocate " + size + " native bytes");
        }
        frees.push(() -> MEM.freeMemory(address));
        return address;
    }

    private Closure.Handle track(Closure.Handle handle) {
        // a disposed slot is reused by other instances, so it goes with the code
        frees.push(handle::dispose);
        return handle;
    }

    // Runs every free, newest first, even when one of them fails.
    private static RuntimeException runFrees(ArrayDeque<Runnable> frees, RuntimeException failure) {
        while (!frees.isEmpty()) {
            try {
                frees.pop().run();
            } catch (RuntimeException e) {
                failure = addFailure(failure, e);
            }
        }
        return failure;
    }

    private static RuntimeException addFailure(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private void borrowImports() {
        var imports = instance.imports();
        try {
            for (int i = 0; i < imports.memoryCount(); i++) {
                if (imports.memory(i).memory() instanceof JffiNativeMemory) {
                    borrow((JffiNativeMemory) imports.memory(i).memory(), "An imported memory");
                }
            }
            for (int i = 0; i < imports.tableCount(); i++) {
                if (imports.table(i).table() instanceof JffiNativeTable) {
                    borrow((JffiNativeTable) imports.table(i).table(), "An imported table");
                }
            }
        } catch (RuntimeException e) {
            // a failed build is never closed, so it must not keep an owner waiting
            releaseBorrowed();
            throw e;
        }
    }

    private void borrow(NativeResource resource, String what) {
        resource.borrowers().add(this, what);
        borrowed.add(resource);
    }

    private void releaseBorrowed() {
        for (var resource : borrowed) {
            resource.borrowers().remove(this);
        }
        borrowed.clear();
    }

    /** The memory and tables this module defines, which are freed with it. */
    private List<NativeResource> ownedResources() {
        var owned = new ArrayList<NativeResource>();
        if (ownsMemory && nativeMemory != null) {
            owned.add(nativeMemory);
        }
        for (int i = instance.imports().tableCount(); i < instance.tableCount(); i++) {
            if (instance.table(i) instanceof JffiNativeTable) {
                owned.add((JffiNativeTable) instance.table(i));
            }
        }
        return owned;
    }

    /** Binds a global this module defines to its slot, as the instance creates it. */
    void bindDefinedGlobal(JffiNativeGlobalInstance global) {
        if (closed) {
            throw new IllegalStateException("This instance has been closed");
        }
        int slot = importGlobalCount + definedGlobalsBound;
        if (slot >= globalCount) {
            throw new IllegalStateException(
                    "A natively compiled instance can only be initialized once");
        }
        definedGlobalsBound++;
        global.bind(globalsBufferAddr, slot);
        boundGlobals.add(global);
    }

    /** Returns the id that the table entries this machine owns hold. */
    long ownerId() {
        return ownerId;
    }

    boolean isClosed() {
        return closed;
    }

    /** Returns the instance this machine executes. */
    Instance instance() {
        return instance;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closing = true;
        var owned = ownedResources();
        try {
            if (callDepth.get() > 0) {
                throw new IllegalStateException(
                        "Cannot close an instance while one of its calls is running");
            }
            // refuses, changing nothing, while another instance still imports one of them
            Borrowers.tryCloseAll(owned);
        } catch (RuntimeException | Error e) {
            closing = false;
            throw e;
        }
        closed = true;
        for (var global : boundGlobals) {
            global.detach();
        }
        releaseBorrowed();
        RuntimeException failure = null;
        for (var resource : owned) {
            try {
                resource.close();
            } catch (RuntimeException e) {
                failure = addFailure(failure, e);
            }
        }
        failure = runFrees(frees, failure);
        NativeResourceCount.freed();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized void abandon() {
        if (closed) {
            return;
        }
        if (!inAnImportedTable()) {
            close();
            return;
        }
        // its entries stay callable until one of the resources they run against closes
        for (var resource : borrowed) {
            resource.borrowers().yieldTo(this);
        }
    }

    private boolean inAnImportedTable() {
        for (var resource : borrowed) {
            if (resource instanceof JffiNativeTable
                    && ((JffiNativeTable) resource).hasWriter(this)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    // --- jffi type mapping ---

    private static Type valTypeToJffiType(ValType type) {
        if (type.equals(ValType.I32)) {
            return Type.SINT32;
        }
        if (type.equals(ValType.I64)) {
            return Type.SINT64;
        }
        if (type.equals(ValType.F32)) {
            return Type.FLOAT;
        }
        if (type.equals(ValType.F64)) {
            return Type.DOUBLE;
        }
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return Type.SINT64;
        }
        throw new WasmEngineException("Unsupported type for native: " + type);
    }

    private static CallContext createEntryTrampolineCallContext(FunctionType funcType) {
        var paramTypes = new ArrayList<Type>();
        paramTypes.add(Type.POINTER); // funcPtr (first arg to entry trampoline)
        paramTypes.add(Type.POINTER); // memBase
        paramTypes.add(Type.POINTER); // ctxPtr
        for (ValType param : funcType.params()) {
            paramTypes.add(valTypeToJffiType(param));
        }

        Type returnType;
        if (funcType.returns().isEmpty()) {
            returnType = Type.VOID;
        } else if (funcType.returns().size() > 1) {
            returnType = Type.SINT64;
        } else {
            returnType = valTypeToJffiType(funcType.returns().get(0));
        }

        return new CallContext(returnType, paramTypes.toArray(new Type[0]));
    }

    // --- Import upcall stubs ---

    private Closure.Handle createImportStub(int funcId, FunctionType funcType) {
        var paramTypes = new ArrayList<Type>();
        paramTypes.add(Type.POINTER); // memBase
        paramTypes.add(Type.POINTER); // ctxPtr
        for (ValType param : funcType.params()) {
            paramTypes.add(valTypeToJffiType(param));
        }

        Type returnType;
        if (funcType.returns().isEmpty()) {
            returnType = Type.VOID;
        } else if (funcType.returns().size() > 1) {
            // Multi-return follows the compiled convention: results go through
            // argsBuffer and the call itself returns a dummy i64.
            returnType = Type.SINT64;
        } else {
            returnType = valTypeToJffiType(funcType.returns().get(0));
        }

        final int fId = funcId;
        final FunctionType ft = funcType;
        Closure closure =
                (Closure.Buffer buf) -> {
                    long result = importDispatchDirect(fId);
                    setClosureReturn(buf, result, ft);
                };

        Closure.Handle handle =
                ClosureManager.getInstance()
                        .newClosure(
                                closure,
                                returnType,
                                paramTypes.toArray(new Type[0]),
                                CallingConvention.DEFAULT);
        return handle;
    }

    private static void setClosureReturn(Closure.Buffer buf, long result, FunctionType funcType) {
        if (funcType.returns().isEmpty()) {
            return;
        }
        if (funcType.returns().size() > 1) {
            buf.setLongReturn(result);
            return;
        }
        ValType retType = funcType.returns().get(0);
        if (retType.equals(ValType.I32)) {
            buf.setIntReturn((int) result);
        } else if (retType.equals(ValType.F32)) {
            buf.setFloatReturn(Float.intBitsToFloat((int) result));
        } else if (retType.equals(ValType.F64)) {
            buf.setDoubleReturn(Double.longBitsToDouble(result));
        } else {
            buf.setLongReturn(result);
        }
    }

    // Host code may have grown the memory since compiled code last saw its size
    private void refreshMemoryPages() {
        var mem = instance.memory();
        if (mem != null) {
            MEM.putInt(ctxBufferAddr + CtxBuffer.MEMORY_PAGES, mem.pages());
        }
    }

    private long importDispatchDirect(int funcId) {
        try {
            int argCount = MEM.getInt(ctxBufferAddr + CtxBuffer.ARG_COUNT);
            long[] args = new long[argCount];
            for (int i = 0; i < argCount; i++) {
                args[i] = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(i));
            }
            if (funcId < numImports) {
                var importFunc = instance.imports().function(funcId);
                long[] result = importFunc.handle().apply(instance, args);
                refreshMemoryPages();
                if (result == null || result.length == 0) {
                    return 0L;
                }
                if (importFunc.functionType().returns().size() > 1) {
                    // Multi-return convention: the caller reads the results back
                    // out of argsBuffer and ignores the returned value.
                    for (int i = 0; i < result.length; i++) {
                        MEM.putLong(argsBufferAddr + CtxBuffer.argOffset(i), result[i]);
                    }
                    return 0L;
                }
                return result[0];
            }
            throw new WasmEngineException("Function " + funcId + " not compiled");
        } catch (Throwable t) {
            recordHostException(t);
            return 0L;
        }
    }

    // --- CALL_INDIRECT trampoline ---

    private Closure.Handle createTrampolineStub() {
        Closure closure =
                (Closure.Buffer buf) -> {
                    long ctxAddr = buf.getLong(0);
                    long result = callIndirectTrampoline(ctxAddr);
                    buf.setLongReturn(result);
                };

        return ClosureManager.getInstance()
                .newClosure(
                        closure, Type.SINT64, new Type[] {Type.SINT64}, CallingConvention.DEFAULT);
    }

    /**
     * Entry point for compiled code that needs the runner: table operations,
     * flagged by a negative argument count, and call_indirect through an entry
     * another instance owns.
     *
     * @param ctxAddr the calling function's context
     * @return the operation's result, or 0 if it recorded an exception
     */
    private long callIndirectTrampoline(long ctxAddr) {
        try {
            int argCount = MEM.getInt(ctxAddr + CtxBuffer.ARG_COUNT);

            // Negative argCount = table operation sentinel
            if (argCount < 0) {
                return handleTableOperation(argCount);
            }
            return callForeignEntry(
                    MEM.getInt(ctxAddr + CtxBuffer.TYPE_ID),
                    MEM.getInt(ctxAddr + CtxBuffer.TABLE_IDX),
                    MEM.getInt(ctxAddr + CtxBuffer.ELEM_IDX),
                    argCount);
        } catch (Throwable t) {
            recordHostException(t);
            return 0L;
        }
    }

    /**
     * Calls a table entry this instance does not own, in the instance that
     * does. Compiled code has already checked the index. The entry's type
     * index is the owner's, so the signature is checked here by structure. The
     * call runs through the owner's machine, against the owner's memory,
     * globals and tables. A trap there becomes an exception that unwinds this
     * instance too.
     *
     * @param typeId the expected type, in this module's index space
     * @param tableIdx the table, in this module's index space
     * @param elemIdx the entry
     * @param argCount the number of arguments in argsBuffer
     * @return a single result as raw bits, or 0 with the results left in
     *     argsBuffer for a multi-value call
     */
    private long callForeignEntry(int typeId, int tableIdx, int elemIdx, int argCount) {
        JffiNativeTable table = nativeTables[tableIdx];
        int funcId = table.ref(elemIdx);
        if (funcId == Value.REF_NULL_VALUE) {
            throw new TrapException("uninitialized element");
        }
        // An entry written with no owner runs in the caller, as in the interpreter
        Instance owner = table.instance(elemIdx);
        if (owner == null) {
            owner = instance;
        }
        var expected = instance.type(typeId);
        var actual = owner.type(owner.functionType(funcId));
        if (!FunctionType.matches(actual, expected, instance.module().typeSection())) {
            throw new TrapException("indirect call type mismatch");
        }
        long[] args = new long[argCount];
        for (int i = 0; i < argCount; i++) {
            args[i] = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(i));
        }
        long[] results = owner.getMachine().call(funcId, args);
        refreshMemoryPages();
        if (results == null || results.length == 0) {
            return 0L;
        }
        if (expected.returns().size() > 1) {
            for (int i = 0; i < results.length; i++) {
                MEM.putLong(argsBufferAddr + CtxBuffer.argOffset(i), results[i]);
            }
            return 0L;
        }
        return results[0];
    }

    private long handleTableOperation(int opCode) {
        switch (opCode) {
            case -1:
                { // table grow fill
                    int oldSize = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int newSize = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int fillValue = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int tblIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    nativeTables[tblIdx].fill(oldSize, newSize, fillValue, instance);
                    nativeTables[tblIdx].limits().grow(newSize - oldSize);
                    break;
                }
            case -2:
                { // table fill
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int end = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int fillValue = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int tblIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    nativeTables[tblIdx].fill(offset, end, fillValue, instance);
                    break;
                }
            case -3:
                { // table copy
                    long srcAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    long dstAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int srcOff = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int dstOff = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int size = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    tableAt(dstAddr).copyFrom(tableAt(srcAddr), srcOff, dstOff, size);
                    break;
                }
            case -4:
                { // table init
                    int tableIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int elemIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int dstOffset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int srcOffset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int size = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    run.endive.runtime.OpcodeImpl.TABLE_INIT(
                            instance, tableIdx, elemIdx, size, srcOffset, dstOffset);
                    break;
                }
            case -5:
                { // elem drop
                    int elemIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    instance.setElement(elemIdx, null);
                    break;
                }
            case -6:
                { // first native table.set into a table
                    int tblIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    nativeTables[tblIdx].registerWriter(this);
                    MEM.putInt(tableWritersAddr + (long) tblIdx * 4, 1);
                    break;
                }
            case -8:
                { // memory.init
                    int segmentId = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int dst = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int src = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int size = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    instance.memory().initPassiveSegment(segmentId, dst, src, size);
                    break;
                }
            case -9:
                { // data.drop
                    int segmentId = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    instance.memory().drop(segmentId);
                    break;
                }
            case -10:
                { // memory.atomic.wait32
                    int addr = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int expected = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long timeout = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    return instance.memory().atomicWait(addr + offset, expected, timeout);
                }
            case -11:
                { // memory.atomic.wait64
                    int addr = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    long expected = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long timeout = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    return instance.memory().atomicWait(addr + offset, expected, timeout);
                }
            case -12:
                { // memory.atomic.notify
                    int addr = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int count = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    return instance.memory().atomicNotify(addr + offset, count);
                }
            default:
                throw new WasmEngineException("Unknown trampoline operation: " + opCode);
        }
        return 0L;
    }

    /**
     * Finds this instance's table whose native buffer is at {@code address}.
     *
     * @param address a table buffer address
     * @return the table
     */
    private JffiNativeTable tableAt(long address) {
        for (JffiNativeTable table : nativeTables) {
            if (table.nativeBufferAddress() == address) {
                return table;
            }
        }
        throw new WasmEngineException("no table at " + address);
    }

    // --- Memory grow upcall stub ---

    private Closure.Handle createMemGrowStub() {
        Closure closure =
                (Closure.Buffer buf) -> {
                    long ctxAddr = buf.getLong(0);
                    long result = memoryGrowHandler(ctxAddr);
                    buf.setLongReturn(result);
                };

        return ClosureManager.getInstance()
                .newClosure(
                        closure, Type.SINT64, new Type[] {Type.SINT64}, CallingConvention.DEFAULT);
    }

    private long memoryGrowHandler(long ctxAddr) {
        try {
            int delta = MEM.getInt(ctxAddr + CtxBuffer.MEM_GROW_DELTA);
            var mem = instance.memory();
            int oldPages = mem.grow(delta);
            if (oldPages != -1 && mem instanceof JffiNativeMemory) {
                JffiNativeMemory nativeMemory = (JffiNativeMemory) mem;
                MEM.putLong(ctxAddr + CtxBuffer.MEM_BASE_ADDR, nativeMemory.nativeAddress());
                MEM.putInt(ctxAddr + CtxBuffer.MEMORY_PAGES, mem.pages());
            }
            return oldPages;
        } catch (Throwable t) {
            recordHostException(t);
            return -1L;
        }
    }

    // --- Globals initialization ---

    /**
     * Compiled code reaches an import through a raw address, so it can only use one
     * this backend built. The message points at the module's own factory rather than
     * a backend-specific one, because that is what works on every platform.
     */
    private static String foreignImportMessage(String kind, Object actual) {
        return "this module is running natively compiled code, which can only use an imported "
                + kind
                + " created by the same backend, but got "
                + actual.getClass().getName()
                + ". Create it through the generated module's imports() factory, which picks"
                + " the right one whether or not native code is available on this platform.";
    }

    private void initializeImportGlobals() {
        if (importGlobalsInitialized || globalCount == 0) {
            return;
        }
        importGlobalsInitialized = true;

        int importGlobalCount =
                (int)
                        instance.module().importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == run.endive.wasm.types.ExternalType
                                                                .GLOBAL)
                                .count();

        for (int i = 0; i < importGlobalCount; i++) {
            var global = instance.global(i);
            if (!(global instanceof JffiNativeGlobalInstance)) {
                throw new WasmEngineException(foreignImportMessage("global", global));
            }
            var nativeGlobal = (JffiNativeGlobalInstance) global;
            if (nativeGlobal.isAdoptable()) {
                // Passed in by the host: adopt it, so what this module writes stays
                // visible through the caller's own object.
                nativeGlobal.bind(globalsBufferAddr, i);
                boundGlobals.add(nativeGlobal);
            } else {
                // Exported by another module, and already sitting where that
                // module's compiled code reads it. Its storage cannot move, so this
                // module starts from its current value.
                MEM.putLong(globalsBufferAddr + (long) i * 8, nativeGlobal.getValue());
            }
        }
    }

    private void initializeNativeTables() {
        if (tablesInitialized) {
            return;
        }

        var module = instance.module();
        int importedTableCount = instance.imports().tableCount();
        int definedTableCount = module.tableSection().tableCount();
        int tableCount = importedTableCount + definedTableCount;

        // checked first, so a call that fails here leaves nothing half set up for the next
        var tables = new JffiNativeTable[tableCount];
        for (int i = 0; i < tableCount; i++) {
            var table = instance.table(i);
            if (!(table instanceof JffiNativeTable)) {
                throw new WasmEngineException(foreignImportMessage("table", table));
            }
            tables[i] = (JffiNativeTable) table;
        }

        if (tableCount > 0 && tablePtrsArrayAddr == 0) {
            this.tablePtrsArrayAddr = allocate((long) tableCount * 8);
            this.tableWritersAddr = allocate((long) tableCount * 4);
        }
        for (int i = 0; i < tableCount; i++) {
            tables[i].resolvePendingRefs(instance);
            MEM.putLong(tablePtrsArrayAddr + (long) i * 8, tables[i].nativeBufferAddress());
        }
        this.nativeTables = tables;
        if (tableCount > 0) {
            MEM.putLong(ctxBufferAddr + CtxBuffer.TABLE_PTRS, tablePtrsArrayAddr);
            MEM.putLong(ctxBufferAddr + CtxBuffer.TABLE_WRITERS_PTR, tableWritersAddr);
        }
        tablesInitialized = true;
    }

    /** Returns how many functions, imported and defined, this machine's function table holds. */
    int functionCount() {
        return (int) (funcTableSize / 8);
    }

    /** Package-private: used by JffiNativeTable to resolve funcId → funcPtr. */
    long getFuncTableAddress() {
        return funcTableAddr;
    }

    long getFuncTypesArrayAddress() {
        return funcTypesArrayAddr;
    }

    /**
     * Marks the context so compiled code unwinds at its next trap check rather
     * than running on. The first throwable wins: it is the one that stopped
     * execution, so a later one would be a symptom of it.
     */
    private void recordHostException(Throwable t) {
        if (pendingException == null) {
            pendingException = t;
        }
        MEM.putInt(ctxBufferAddr + CtxBuffer.TRAP_CODE, CtxBuffer.TRAP_HOST_EXCEPTION);
    }

    private static WasmEngineException trapException(int trapCode) {
        if (trapCode == CtxBuffer.TRAP_DIV_BY_ZERO) {
            return new TrapException("integer divide by zero");
        }
        if (trapCode == CtxBuffer.TRAP_INT_OVERFLOW) {
            return new TrapException("integer overflow");
        }
        if (trapCode == CtxBuffer.TRAP_UNREACHABLE) {
            return new TrapException("unreachable");
        }
        if (trapCode == CtxBuffer.TRAP_TRUNC_OVERFLOW) {
            return new TrapException("integer overflow");
        }
        if (trapCode == CtxBuffer.TRAP_TRUNC_NAN) {
            return new TrapException("invalid conversion to integer");
        }
        if (trapCode == CtxBuffer.TRAP_OOB) {
            return new WasmRuntimeException("out of bounds memory access");
        }
        if (trapCode == CtxBuffer.TRAP_CALL_STACK_EXHAUSTED) {
            return new TrapException("call stack exhausted");
        }
        if (trapCode == CtxBuffer.TRAP_TABLE_OOB) {
            return new TrapException("out of bounds table access");
        }
        if (trapCode == CtxBuffer.TRAP_UNDEFINED_ELEMENT) {
            return new TrapException("undefined element");
        }
        if (trapCode == CtxBuffer.TRAP_UNINITIALIZED_ELEMENT) {
            return new TrapException("uninitialized element");
        }
        if (trapCode == CtxBuffer.TRAP_INDIRECT_CALL_TYPE_MISMATCH) {
            return new TrapException("indirect call type mismatch");
        }
        if (trapCode == CtxBuffer.TRAP_UNALIGNED_ATOMIC) {
            return new TrapException("unaligned atomic");
        }
        if (trapCode == CtxBuffer.TRAP_INTERRUPTED) {
            return new WasmInterruptedException("Thread interrupted");
        }
        return new WasmEngineException("trap: unknown code " + trapCode);
    }

    // --- Native function invocation ---

    private long invokeViaEntryTrampoline(
            Function trampoline,
            FunctionType funcType,
            long funcAddr,
            long memBase,
            long ctxPtr,
            long[] wasmArgs) {
        // nativeArgCount = funcPtr + memBase + ctxPtr + wasm params
        int nativeArgCount = 3 + wasmArgs.length;
        CallContext trampolineCallCtx = trampoline.getCallContext();
        long trampolineAddr = trampoline.getFunctionAddress();

        switch (nativeArgCount) {
            case 3:
                return INVOKER.invokeN3(
                        trampolineCallCtx, trampolineAddr, funcAddr, memBase, ctxPtr);
            case 4:
                return INVOKER.invokeN4(
                        trampolineCallCtx, trampolineAddr, funcAddr, memBase, ctxPtr, wasmArgs[0]);
            case 5:
                return INVOKER.invokeN5(
                        trampolineCallCtx,
                        trampolineAddr,
                        funcAddr,
                        memBase,
                        ctxPtr,
                        wasmArgs[0],
                        wasmArgs[1]);
            case 6:
                return INVOKER.invokeN6(
                        trampolineCallCtx,
                        trampolineAddr,
                        funcAddr,
                        memBase,
                        ctxPtr,
                        wasmArgs[0],
                        wasmArgs[1],
                        wasmArgs[2]);
            default:
                // >6 args: use HeapInvocationBuffer
                return invokeViaBufferWithTrampoline(
                        trampoline, funcType, funcAddr, memBase, ctxPtr, wasmArgs);
        }
    }

    private long invokeViaBufferWithTrampoline(
            Function func,
            FunctionType funcType,
            long funcAddr,
            long memBase,
            long ctxPtr,
            long[] wasmArgs) {
        var buffer = new HeapInvocationBuffer(func);
        buffer.putAddress(funcAddr); // funcPtr (first arg to entry trampoline)
        buffer.putAddress(memBase);
        buffer.putAddress(ctxPtr);
        for (int i = 0; i < wasmArgs.length; i++) {
            ValType paramType = funcType.params().get(i);
            if (paramType.equals(ValType.I32)) {
                buffer.putInt((int) wasmArgs[i]);
            } else if (paramType.equals(ValType.F32)) {
                buffer.putFloat(Float.intBitsToFloat((int) wasmArgs[i]));
            } else if (paramType.equals(ValType.F64)) {
                buffer.putDouble(Double.longBitsToDouble(wasmArgs[i]));
            } else {
                buffer.putLong(wasmArgs[i]);
            }
        }

        return INVOKER.invokeLong(func, buffer);
    }

    // --- Main dispatch ---

    @Override
    public long[] call(int funcId, long[] args) throws WasmEngineException {
        if (closed) {
            // Its native code and buffers are gone, but a table may still hold its functions
            throw new WasmEngineException("instance is closed");
        }
        if (funcId < numImports) {
            // Host import — delegate directly
            var imprt = instance.imports().function(funcId);
            return imprt.handle().apply(instance, args);
        }

        var funcType = funcTypes[funcId];
        if (entryTrampolines[funcId] == null) {
            throw new WasmEngineException("Function " + funcId + " not compiled");
        }

        boolean outermostCall = enterCall();
        // a nested instance shares the stack budget of the one running on this thread
        JffiNativeMachine outer = null;
        if (outermostCall) {
            outer = RUNNING.get();
            RUNNING.set(this);
        }
        try {
            long funcAddr = MEM.getLong(funcTableAddr + (long) funcId * 8);
            initializeImportGlobals();
            initializeNativeTables();

            // Re-anchor the stack guard only for a call that starts on this
            // stack. A host function calling back in has to keep measuring
            // against where the outer call began, or every level moves the
            // limit deeper and the guard stops firing.
            if (outermostCall) {
                MEM.putLong(
                        ctxBufferAddr + CtxBuffer.STACK_LIMIT,
                        outer == null ? 0L : outer.stackLimit());
            }

            if (!memBaseInitialized) {
                var mem = instance.memory();
                if (mem instanceof JffiNativeMemory) {
                    cachedMemBase = ((JffiNativeMemory) mem).nativeAddress();
                    MEM.putLong(ctxBufferAddr + CtxBuffer.MEM_BASE_ADDR, cachedMemBase);
                    MEM.putLong(
                            ctxBufferAddr + CtxBuffer.MEMORY_PAGES_PTR,
                            ((JffiNativeMemory) mem).pagesAddress());
                } else if (mem != null) {
                    throw new WasmEngineException(foreignImportMessage("memory", mem));
                } else {
                    cachedMemBase = 0L;
                }
                memBaseInitialized = true;
            }
            refreshMemoryPages();

            if (Thread.currentThread().isInterrupted()) {
                throw new WasmInterruptedException("Thread interrupted");
            }

            // nested calls run inside the outermost call's watch
            InterruptWatchdog.Registration watchdog =
                    outermostCall ? InterruptWatchdog.enter(interruptFlag) : null;
            long result;
            try {
                result =
                        invokeViaEntryTrampoline(
                                entryTrampolines[funcId],
                                funcType,
                                funcAddr,
                                cachedMemBase,
                                ctxBufferAddr,
                                args);
            } finally {
                if (watchdog != null) {
                    // exit first, so the poller cannot raise the flag after the clear
                    InterruptWatchdog.exit(watchdog);
                }
                clearInterrupt();
            }

            // Check for exceptions from upcall stubs first — a host function
            // may throw (e.g. WasiExitException from proc_exit) and then the
            // native code hits unreachable, setting both pendingException and
            // a trap code.  The pending exception is the real cause.
            if (pendingException != null) {
                var ex = pendingException;
                pendingException = null;
                MEM.putInt(ctxBufferAddr + CtxBuffer.TRAP_CODE, 0);
                sneakyThrow(ex);
            }

            // Check for traps
            int trapCode = MEM.getInt(ctxBufferAddr + CtxBuffer.TRAP_CODE);
            if (trapCode != 0) {
                MEM.putInt(ctxBufferAddr + CtxBuffer.TRAP_CODE, 0);
                if (trapCode == CtxBuffer.TRAP_INTERRUPTED) {
                    Thread.currentThread().interrupt();
                }
                throw trapException(trapCode);
            }

            if (funcType.returns().isEmpty()) {
                return new long[0];
            }

            if (funcType.returns().size() > 1) {
                // Multi-return: read values from argsBuffer
                long[] results = new long[funcType.returns().size()];
                for (int i = 0; i < results.length; i++) {
                    long raw = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(i));
                    results[i] = narrowReturnValue(raw, funcType.returns().get(i));
                }
                return results;
            }

            return new long[] {result};
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            sneakyThrow(e);
            throw new AssertionError("unreachable");
        } finally {
            if (outermostCall) {
                if (outer == null) {
                    RUNNING.remove();
                } else {
                    RUNNING.set(outer);
                }
            }
            callDepth.decrementAndGet();
            // Prevent the JIT from considering this machine unreachable during
            // the native call, which would let GC collect and close() free
            // native memory while code is executing.
            Reference.reachabilityFence(this);
        }
    }

    // anchored at the outermost call's entry, then shared by everything nested in it
    private long stackLimit() {
        return MEM.getLong(ctxBufferAddr + CtxBuffer.STACK_LIMIT);
    }

    /**
     * Counts a call in before it touches native state. A call that starts while
     * a close is deciding waits for it, and fails if the instance closed.
     *
     * @return whether this is the outermost call on this instance
     */
    private boolean enterCall() {
        while (true) {
            int depth = callDepth.incrementAndGet();
            if (!closing) {
                return depth == 1;
            }
            callDepth.decrementAndGet();
            synchronized (this) {
                if (closed) {
                    throw new WasmEngineException("instance is closed");
                }
            }
        }
    }

    private void raiseInterruptFlag() {
        CHECKED_MEM.putLong(ctxBufferAddr + CtxBuffer.INTERRUPT_FLAG, 1L);
    }

    private void clearInterrupt() {
        CHECKED_MEM.putLong(ctxBufferAddr + CtxBuffer.INTERRUPT_FLAG, 0L);
    }

    private static long narrowReturnValue(long raw, ValType type) {
        if (type.equals(ValType.I32)) {
            return (int) raw;
        }
        if (type.equals(ValType.F32)) {
            return Value.floatToLong(Float.intBitsToFloat((int) raw));
        }
        if (type.equals(ValType.F64)) {
            return Value.doubleToLong(Double.longBitsToDouble(raw));
        }
        return raw; // I64
    }
}
