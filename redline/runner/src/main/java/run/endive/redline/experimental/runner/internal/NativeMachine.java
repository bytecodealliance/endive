package run.endive.redline.experimental.runner.internal;

import static java.util.Objects.requireNonNullElse;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
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
 * via Cranelift and executes them through Panama FFM.
 *
 * <p>Calling convention for all compiled functions:
 * <pre>
 *   param 0: memBase  (i64/ADDRESS) — pointer to linear memory
 *   param 1: ctxPtr   (i64/ADDRESS) — pointer to call context struct
 *   param 2+: Wasm function parameters
 *   return: Wasm return value
 * </pre>
 *
 * <p>See {@link CtxBuffer} for the full layout definition.
 */
public final class NativeMachine implements Machine {

    private static final int CTX_SIZE = CtxBuffer.CTX_SIZE;
    private static final long PAGE_SIZE = 4096;

    // Never reused, unlike a context address, so a stale entry cannot match a new instance
    private static final AtomicLong OWNER_IDS = new AtomicLong();

    // Conversion handles for adapting downcalls to uniform (MS, MS, long[]) → long.
    // These use Wasm bit-reinterpretation (not numeric casts).
    private static final MethodHandle LONG_TO_FLOAT;
    private static final MethodHandle LONG_TO_DOUBLE;
    private static final MethodHandle FLOAT_TO_LONG;
    private static final MethodHandle DOUBLE_TO_LONG;

    static {
        try {
            var lookup = MethodHandles.lookup();
            LONG_TO_FLOAT =
                    lookup.findStatic(
                            Value.class,
                            "longToFloat",
                            MethodType.methodType(float.class, long.class));
            LONG_TO_DOUBLE =
                    lookup.findStatic(
                            Value.class,
                            "longToDouble",
                            MethodType.methodType(double.class, long.class));
            FLOAT_TO_LONG =
                    lookup.findStatic(
                            Value.class,
                            "floatToLong",
                            MethodType.methodType(long.class, float.class));
            DOUBLE_TO_LONG =
                    lookup.findStatic(
                            Value.class,
                            "doubleToLong",
                            MethodType.methodType(long.class, double.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Arena arena;
    private final Instance instance;
    private final MethodHandle[] downcalls;
    private final MemorySegment codeRegion;
    private final long codeRegionSize;
    private final MemorySegment ctxBuffer;
    private final MemorySegment funcTable;
    private final MemorySegment argsBuffer;
    private final MemorySegment globalsBuffer;
    private final MemorySegment funcTypesArray;
    private final long ownerId = OWNER_IDS.incrementAndGet();
    private MemorySegment tablePtrsArray;
    private MemorySegment tableWriters;
    private NativeTable[] nativeTables;
    private boolean tablesInitialized;
    private final int numImports;
    private final int importGlobalCount;
    private final int globalCount;
    private int definedGlobalsBound;
    private boolean importGlobalsInitialized;
    private MemorySegment cachedMemBase;
    private boolean memBaseInitialized;
    private NativeMemory nativeMemory;
    private volatile Throwable pendingException;
    // the machine whose outermost call is running on this thread
    private static final ThreadLocal<NativeMachine> RUNNING = new ThreadLocal<>();
    // calls under way on any thread, which close() refuses to run under
    private final AtomicInteger callDepth = new AtomicInteger();
    // set by close() before it looks at callDepth, and kept once it succeeds
    private volatile boolean closing;
    private final InterruptWatchdog.InterruptSink interruptFlag = this::raiseInterruptFlag;
    private boolean ownsMemory;
    private boolean closed;
    private final List<NativeResource> borrowed = new ArrayList<>();
    private final List<NativeGlobalInstance> boundGlobals = new ArrayList<>();

    /** Takes ownership of {@code arena}: closing this machine closes it. */
    public NativeMachine(Instance instance, Arena arena, NativeCode precompiledCode) {
        this.instance = instance;
        this.arena = arena;
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
        this.downcalls = new MethodHandle[totalFuncs];

        // Allocate call context buffer
        ctxBuffer = arena.allocate(CTX_SIZE, 8);

        // Allocate function pointer table (one i64 per function)
        funcTable = arena.allocate((long) totalFuncs * 8, 8);

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
        // One slot per global, imported or defined, which bind() moves them onto
        this.globalsBuffer =
                globalCount > 0 ? arena.allocate((long) globalCount * 8, 8) : MemorySegment.NULL;

        // Allocate args buffer (separate from ctxBuffer, no fixed arg limit)
        this.argsBuffer = arena.allocate((long) CtxBuffer.ARGS_BUFFER_CAPACITY * 8, 8);

        // Allocate funcTypes array with canonical type indices.
        // Structurally equal FunctionTypes get the same canonical index,
        // enabling correct call_indirect type checking with duplicate types.
        int[] canonicalTypeMap = TypeMapUtils.buildCanonicalTypeMap(module);
        this.funcTypesArray = arena.allocate((long) totalFuncs * 4, 4);
        for (int i = 0; i < numImports; i++) {
            int rawType = instance.functionType(i);
            funcTypesArray.set(ValueLayout.JAVA_INT, (long) i * 4, canonicalTypeMap[rawType]);
        }
        for (int i = 0; i < module.functionSection().functionCount(); i++) {
            int funcId = numImports + i;
            int rawType = module.functionSection().getFunctionType(i);
            funcTypesArray.set(ValueLayout.JAVA_INT, (long) funcId * 4, canonicalTypeMap[rawType]);
        }

        // NativeTables: tables are created by Instance via tableFactory, but Instance
        // hasn't finished constructing yet (tables created after Machine). We'll
        // populate the tablePtrs lazily on first call() once tables exist.
        this.nativeTables = null;
        this.tablePtrsArray = MemorySegment.NULL;
        this.tablesInitialized = false;

        // Host stubs are called by compiled code with the platform ABI
        MemorySegment trampolineStub = createTrampolineStub();
        MemorySegment memGrowStub = createMemGrowStub();
        MemorySegment[] importStubs = new MemorySegment[numImports];
        for (int funcId = 0; funcId < numImports; funcId++) {
            var funcType = instance.imports().function(funcId).functionType();
            importStubs[funcId] = createImportStub(funcId, funcType);
        }

        // Write pointers to ctxBuffer
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.FUNC_TABLE_PTR, funcTable.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.TRAMPOLINE_PTR, trampolineStub.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.ARGS_PTR, argsBuffer.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.GLOBALS_PTR, globalsBuffer.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.MEM_GROW_PTR, memGrowStub.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.TABLE_PTRS, tablePtrsArray.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.FUNC_TYPES_PTR, funcTypesArray.address());
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.MEMMOVE_PTR, PanamaExecutor.MEMMOVE_ADDR);
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.MEMSET_PTR, PanamaExecutor.MEMSET_ADDR);
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.OWNER_ID, ownerId);

        byte[] image = code.image();
        this.codeRegionSize = (Math.max(image.length, 1) + PAGE_SIZE - 1) & -PAGE_SIZE;

        try {
            codeRegion = PanamaExecutor.mmapCode(codeRegionSize);
            // unmapped with the arena; downcalls keep this global view and don't acquire it
            codeRegion.reinterpret(arena, PanamaExecutor::unmapQuietly);
            MemorySegment.copy(MemorySegment.ofArray(image), 0, codeRegion, 0, image.length);
            for (int funcId = 0; funcId < numImports; funcId++) {
                codeRegion.set(
                        ValueLayout.JAVA_LONG_UNALIGNED,
                        code.importStubSlotOffset(funcId),
                        importStubs[funcId].address());
            }
            PanamaExecutor.mprotectExec(codeRegion, codeRegionSize);

            // Compiled code reaches imports through their trampolines
            for (int funcId = 0; funcId < numImports; funcId++) {
                funcTable.setAtIndex(
                        ValueLayout.JAVA_LONG,
                        funcId,
                        codeRegion.address() + code.importTrampolineOffset(funcId));
            }

            for (int i = 0; i < code.functionBodyCount(); i++) {
                if (code.isCompiled(i)) {
                    int funcId = numImports + i;
                    var funcType =
                            (FunctionType)
                                    module.typeSection()
                                            .getType(module.functionSection().getFunctionType(i));
                    MemorySegment codePtr = codeRegion.asSlice(code.bodyOffset(i));
                    funcTable.setAtIndex(ValueLayout.JAVA_LONG, funcId, codePtr.address());
                    downcalls[funcId] =
                            createDowncallViaTrampoline(
                                    codeRegion.asSlice(code.entryTrampolineOffset(i)),
                                    codePtr,
                                    funcType);
                }
            }
        } catch (Throwable e) {
            throw new WasmEngineException("Failed to set up native code", e);
        }

        this.nativeMemory = instance.memory() instanceof NativeMemory nm ? nm : null;
        // An imported memory outlives this instance and may back others, so only
        // a memory this module defines is ours to close.
        this.ownsMemory = instance.imports().memoryCount() == 0;
        // last, so nothing after it can fail and leave an owner waiting on us
        borrowImports();
        NativeResourceCount.allocated();
    }

    private void borrowImports() {
        var imports = instance.imports();
        try {
            for (int i = 0; i < imports.memoryCount(); i++) {
                if (imports.memory(i).memory() instanceof NativeMemory memory) {
                    borrow(memory, "An imported memory");
                }
            }
            for (int i = 0; i < imports.tableCount(); i++) {
                if (imports.table(i).table() instanceof NativeTable table) {
                    borrow(table, "An imported table");
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
            if (instance.table(i) instanceof NativeTable table) {
                owned.add(table);
            }
        }
        return owned;
    }

    /** Binds a global this module defines to its slot, as the instance creates it. */
    void bindDefinedGlobal(NativeGlobalInstance global) {
        if (closed) {
            throw new IllegalStateException("This instance has been closed");
        }
        int slot = importGlobalCount + definedGlobalsBound;
        if (slot >= globalCount) {
            throw new IllegalStateException(
                    "A natively compiled instance can only be initialized once");
        }
        definedGlobalsBound++;
        global.bind(globalsBuffer, slot);
        boundGlobals.add(global);
    }

    private long[] globalValues() {
        var values = new long[boundGlobals.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = boundGlobals.get(i).getValue();
        }
        return values;
    }

    private void detachGlobals(long[] values) {
        for (int i = 0; i < values.length; i++) {
            boundGlobals.get(i).detach(values[i]);
        }
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
        long[] values;
        try {
            if (callDepth.get() > 0) {
                throw new IllegalStateException(
                        "Cannot close an instance while one of its calls is running");
            }
            // refuses, changing nothing, while another instance still imports one of them
            Borrowers.tryCloseAll(owned);
            // read now: the globals buffer goes with the arena
            values = globalValues();
            try {
                // frees the code, stubs and buffers, or fails while a downcall holds the ctx
                arena.close();
            } catch (IllegalStateException e) {
                Borrowers.reopenAll(owned);
                throw new IllegalStateException(
                        "Cannot close an instance while one of its calls is running", e);
            }
        } catch (RuntimeException | Error e) {
            closing = false;
            throw e;
        }
        closed = true;
        detachGlobals(values);
        releaseBorrowed();
        NativeResourceCount.freed();
        RuntimeException failure = null;
        for (var resource : owned) {
            try {
                resource.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
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
            if (resource instanceof NativeTable table && table.hasWriter(this)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    // --- Downcall creation (Java → native via entry trampoline) ---

    private MethodHandle createDowncallViaTrampoline(
            MemorySegment trampolinePtr, MemorySegment funcCodePtr, FunctionType funcType) {
        // Entry trampoline has platform ABI signature:
        // (ADDRESS funcPtr, ADDRESS memBase, ADDRESS ctxPtr, typed_params...) -> typed_return
        var layouts = new ArrayList<ValueLayout>();
        layouts.add(ValueLayout.ADDRESS); // funcPtr
        layouts.add(ValueLayout.ADDRESS); // memBase
        layouts.add(ValueLayout.ADDRESS); // ctxPtr

        for (ValType param : funcType.params()) {
            layouts.add(valTypeToLayout(param));
        }

        ValueLayout returnLayout = null;
        if (!funcType.returns().isEmpty()) {
            if (funcType.returns().size() > 1) {
                returnLayout = ValueLayout.JAVA_LONG;
            } else {
                returnLayout = valTypeToLayout(funcType.returns().get(0));
            }
        }

        FunctionDescriptor desc;
        if (returnLayout != null) {
            desc = FunctionDescriptor.of(returnLayout, layouts.toArray(new ValueLayout[0]));
        } else {
            desc = FunctionDescriptor.ofVoid(layouts.toArray(new ValueLayout[0]));
        }

        MethodHandle handle = Linker.nativeLinker().downcallHandle(trampolinePtr, desc);
        // Bind the wasm function pointer as the first argument so the call site
        // signature remains (memBase, ctxPtr, args...) -> result
        handle = MethodHandles.insertArguments(handle, 0, funcCodePtr);
        return adaptHandle(handle, funcType);
    }

    /**
     * Adapt a native downcall handle to a uniform type:
     * {@code (MemorySegment memBase, MemorySegment ctxPtr, long[] wasmArgs) -> long}.
     *
     * <p>This eliminates Object[] boxing and enables invokeExact at the call site.
     * Float/double conversions use Wasm bit-reinterpretation (not numeric casts).
     */
    private static MethodHandle adaptHandle(MethodHandle handle, FunctionType funcType) {
        int paramCount = funcType.params().size();

        // Step 1: Adapt return type to long
        if (handle.type().returnType() == void.class) {
            // Void → long: call void handle for side effects, return 0L
            MethodHandle zero =
                    MethodHandles.dropArguments(
                            MethodHandles.constant(long.class, 0L),
                            0,
                            handle.type().parameterList());
            handle = MethodHandles.foldArguments(zero, handle);
        } else if (!funcType.returns().isEmpty() && funcType.returns().size() == 1) {
            ValType retType = funcType.returns().get(0);
            if (retType.equals(ValType.F32)) {
                handle = MethodHandles.filterReturnValue(handle, FLOAT_TO_LONG);
            } else if (retType.equals(ValType.F64)) {
                handle = MethodHandles.filterReturnValue(handle, DOUBLE_TO_LONG);
            }
            // I32 → long widening and I64 identity handled by asType below
        }
        // Multi-return: native returns long dummy, no conversion needed

        // Step 2: Filter float/double params (bit-reinterpret long → float/double)
        for (int i = 0; i < paramCount; i++) {
            ValType paramType = funcType.params().get(i);
            if (paramType.equals(ValType.F32)) {
                handle = MethodHandles.filterArguments(handle, i + 2, LONG_TO_FLOAT);
            } else if (paramType.equals(ValType.F64)) {
                handle = MethodHandles.filterArguments(handle, i + 2, LONG_TO_DOUBLE);
            }
        }

        // Step 3: Normalize remaining params to long and return to long.
        // explicitCastArguments handles long→int narrowing (asType only does widening).
        var targetParams = new Class<?>[2 + paramCount];
        targetParams[0] = MemorySegment.class;
        targetParams[1] = MemorySegment.class;
        for (int i = 0; i < paramCount; i++) {
            targetParams[i + 2] = long.class;
        }
        handle =
                MethodHandles.explicitCastArguments(
                        handle, MethodType.methodType(long.class, targetParams));

        // Step 4: Spread wasm params from long[]
        handle = handle.asSpreader(long[].class, paramCount);
        // Final type: (MemorySegment, MemorySegment, long[]) → long

        return handle;
    }

    // --- Import upcall stubs (native → Java for host imports) ---

    private MemorySegment createImportStub(int funcId, FunctionType funcType) {
        try {
            // Build the native descriptor matching compiled function convention:
            // (ADDRESS memBase, ADDRESS ctxPtr, typed_params...) -> typed_return
            var layouts = new ArrayList<ValueLayout>();
            layouts.add(ValueLayout.ADDRESS); // memBase
            layouts.add(ValueLayout.ADDRESS); // ctxPtr
            for (ValType param : funcType.params()) {
                layouts.add(valTypeToLayout(param));
            }

            // Multi-return follows the compiled convention: results go through
            // argsBuffer and the call itself returns a dummy i64.
            boolean multiReturn = funcType.returns().size() > 1;

            ValueLayout returnLayout = null;
            if (multiReturn) {
                returnLayout = ValueLayout.JAVA_LONG;
            } else if (!funcType.returns().isEmpty()) {
                returnLayout = valTypeToLayout(funcType.returns().get(0));
            }

            FunctionDescriptor desc;
            if (returnLayout != null) {
                desc = FunctionDescriptor.of(returnLayout, layouts.toArray(new ValueLayout[0]));
            } else {
                desc = FunctionDescriptor.ofVoid(layouts.toArray(new ValueLayout[0]));
            }

            // Build Java param types for the MethodHandle
            var targetParamTypes = new ArrayList<Class<?>>();
            targetParamTypes.add(MemorySegment.class); // memBase
            targetParamTypes.add(MemorySegment.class); // ctxPtr
            for (ValType param : funcType.params()) {
                targetParamTypes.add(valTypeToJavaClass(param));
            }

            // importDispatchDirect reads args from ctxBuffer (written by CALL handler)
            MethodHandle directHandler =
                    MethodHandles.lookup()
                            .bind(
                                    this,
                                    "importDispatchDirect",
                                    MethodType.methodType(long.class, int.class));
            directHandler = MethodHandles.insertArguments(directHandler, 0, funcId);
            // Now: () -> long

            // Drop all native params (the stub ignores them, reads from ctxBuffer)
            MethodHandle dropper =
                    MethodHandles.dropArguments(
                            directHandler, 0, targetParamTypes.toArray(new Class[0]));

            // Cast return type to match native descriptor
            if (returnLayout == null) {
                // Void function: discard the long return value
                var voidType =
                        MethodType.methodType(void.class, targetParamTypes.toArray(new Class[0]));
                dropper = dropper.asType(voidType);
            } else if (!multiReturn && !funcType.returns().isEmpty()) {
                var retClass = valTypeToJavaClass(funcType.returns().get(0));
                if (retClass.equals(float.class)) {
                    // The long carries the f32 bit pattern, so it has to be
                    // reinterpreted. A cast would convert numerically and turn the
                    // bits of 1.5f into 1.06954752E9f.
                    dropper = MethodHandles.filterReturnValue(dropper, LONG_TO_FLOAT);
                } else if (retClass.equals(double.class)) {
                    dropper = MethodHandles.filterReturnValue(dropper, LONG_TO_DOUBLE);
                } else if (!retClass.equals(long.class)) {
                    // i32: the value is the low 32 bits, so truncation is correct.
                    dropper =
                            MethodHandles.explicitCastArguments(
                                    dropper,
                                    MethodType.methodType(
                                            retClass, targetParamTypes.toArray(new Class[0])));
                }
            }

            return Linker.nativeLinker().upcallStub(dropper, desc, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new WasmEngineException("Failed to create import stub for func " + funcId, e);
        }
    }

    /**
     * Dispatches a function call from native code. Reads args from ctxBuffer.
     * Called by import upcall stubs and bridge stubs for non-native functions.
     */
    // Host code may have grown the memory since compiled code last saw its size
    private void refreshMemoryPages() {
        var mem = instance.memory();
        if (mem != null) {
            ctxBuffer.set(ValueLayout.JAVA_INT, CtxBuffer.MEMORY_PAGES, mem.pages());
        }
    }

    @SuppressWarnings("unused")
    private long importDispatchDirect(int funcId) {
        try {
            int argCount = ctxBuffer.get(ValueLayout.JAVA_INT, CtxBuffer.ARG_COUNT);
            long[] args = new long[argCount];
            for (int i = 0; i < argCount; i++) {
                args[i] = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i));
            }
            if (funcId < numImports) {
                var importFunc = instance.imports().function(funcId);
                long[] result = importFunc.handle().apply(instance, args);
                refreshMemoryPages();
                if (result == null || result.length == 0) {
                    return 0L;
                }
                if (importFunc.functionType().returns().size() > 1) {
                    // Multi-return convention: the caller reads the results back out
                    // of argsBuffer and ignores the returned value.
                    for (int i = 0; i < result.length; i++) {
                        argsBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i), result[i]);
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

    private MemorySegment createTrampolineStub() {
        try {
            MethodHandle handler =
                    MethodHandles.lookup()
                            .bind(
                                    this,
                                    "callIndirectTrampoline",
                                    MethodType.methodType(long.class, long.class));
            var desc = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
            return Linker.nativeLinker().upcallStub(handler, desc, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new WasmEngineException("Failed to create trampoline stub", e);
        }
    }

    /**
     * Entry point for compiled code that needs the runner: table operations,
     * flagged by a negative argument count, and call_indirect through an entry
     * another instance owns.
     *
     * @param ctxAddr the calling function's context
     * @return the operation's result, or 0 if it recorded an exception
     */
    @SuppressWarnings("unused")
    private long callIndirectTrampoline(long ctxAddr) {
        try {
            var ctx = MemorySegment.ofAddress(ctxAddr).reinterpret(CTX_SIZE);
            int argCount = ctx.get(ValueLayout.JAVA_INT, CtxBuffer.ARG_COUNT);
            if (argCount < 0) {
                return handleTableOperation(argCount);
            }
            return callForeignEntry(
                    ctx.get(ValueLayout.JAVA_INT, CtxBuffer.TYPE_ID),
                    ctx.get(ValueLayout.JAVA_INT, CtxBuffer.TABLE_IDX),
                    ctx.get(ValueLayout.JAVA_INT, CtxBuffer.ELEM_IDX),
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
        NativeTable table = nativeTables[tableIdx];
        int funcId = table.ref(elemIdx);
        if (funcId == Value.REF_NULL_VALUE) {
            throw new TrapException("uninitialized element");
        }
        // An entry written with no owner runs in the caller, as in the interpreter
        Instance owner = requireNonNullElse(table.instance(elemIdx), instance);
        var expected = instance.type(typeId);
        var actual = owner.type(owner.functionType(funcId));
        if (!FunctionType.matches(actual, expected, instance.module().typeSection())) {
            throw new TrapException("indirect call type mismatch");
        }
        long[] args = new long[argCount];
        for (int i = 0; i < argCount; i++) {
            args[i] = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i));
        }
        long[] results = owner.getMachine().call(funcId, args);
        refreshMemoryPages();
        if (results == null || results.length == 0) {
            return 0L;
        }
        if (expected.returns().size() > 1) {
            for (int i = 0; i < results.length; i++) {
                argsBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i), results[i]);
            }
            return 0L;
        }
        return results[0];
    }

    private long handleTableOperation(int opCode) {
        switch (opCode) {
            case -1 -> { // table grow fill
                int oldSize = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int newSize = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int fillValue = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int tblIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(4));
                nativeTables[tblIdx].fill(oldSize, newSize, fillValue, instance);
                // Update TableLimits so import validation sees the grown size
                nativeTables[tblIdx].limits().grow(newSize - oldSize);
            }
            case -2 -> { // table fill
                int offset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int end = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int fillValue = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int tblIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(4));
                nativeTables[tblIdx].fill(offset, end, fillValue, instance);
            }
            case -3 -> { // table copy
                long srcAddr = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                long dstAddr = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int srcOff = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int dstOff = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(4));
                tableAt(dstAddr).copyFrom(tableAt(srcAddr), srcOff, dstOff, size);
            }
            case -4 -> { // table init
                int tableIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int elemIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int dstOffset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int srcOffset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(4));
                // Instance.table(tableIdx) returns NativeTable (set via tableFactory).
                // NativeTable.setRef resolves funcId → funcPtr+typeIdx via funcResolver.
                run.endive.runtime.OpcodeImpl.TABLE_INIT(
                        instance, tableIdx, elemIdx, size, srcOffset, dstOffset);
            }
            case -5 -> { // elem drop
                int elemIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                instance.setElement(elemIdx, null);
            }
            case -6 -> { // first native table.set into a table
                int tblIdx = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                nativeTables[tblIdx].registerWriter(this);
                tableWriters.set(ValueLayout.JAVA_INT, (long) tblIdx * 4, 1);
            }
            case -8 -> { // memory.init
                int segmentId = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int dst = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int src = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                int size = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                instance.memory().initPassiveSegment(segmentId, dst, src, size);
            }
            case -9 -> { // data.drop
                int segmentId = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                instance.memory().drop(segmentId);
            }
            case -10 -> { // memory.atomic.wait32
                int addr = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int expected = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int offset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                long timeout = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                return instance.memory().atomicWait(addr + offset, expected, timeout);
            }
            case -11 -> { // memory.atomic.wait64
                int addr = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                long expected = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int offset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                long timeout = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(3));
                return instance.memory().atomicWait(addr + offset, expected, timeout);
            }
            case -12 -> { // memory.atomic.notify
                int addr = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(0));
                int count = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(1));
                int offset = (int) argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(2));
                return instance.memory().atomicNotify(addr + offset, count);
            }
            default -> throw new WasmEngineException("Unknown trampoline operation: " + opCode);
        }
        return 0L;
    }

    /**
     * Finds this instance's table whose native buffer is at {@code address}.
     *
     * @param address a table buffer address
     * @return the table
     */
    private NativeTable tableAt(long address) {
        for (NativeTable table : nativeTables) {
            if (table.nativeBuffer().address() == address) {
                return table;
            }
        }
        throw new WasmEngineException("no table at " + address);
    }

    // --- Memory grow upcall stub ---

    private MemorySegment createMemGrowStub() {
        try {
            MethodHandle handler =
                    MethodHandles.lookup()
                            .bind(
                                    this,
                                    "memoryGrowHandler",
                                    MethodType.methodType(long.class, long.class));
            var desc = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
            return Linker.nativeLinker().upcallStub(handler, desc, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new WasmEngineException("Failed to create memory grow stub", e);
        }
    }

    @SuppressWarnings("unused")
    private long memoryGrowHandler(long ctxAddr) {
        try {
            var ctx = MemorySegment.ofAddress(ctxAddr).reinterpret(CTX_SIZE);
            int delta = ctx.get(ValueLayout.JAVA_INT, CtxBuffer.MEM_GROW_DELTA);
            var mem = instance.memory();
            int oldPages = mem.grow(delta);
            // Update memory base and page count in ctxBuffer
            if (oldPages != -1 && mem instanceof NativeMemory nativeMemory) {
                ctx.set(
                        ValueLayout.JAVA_LONG,
                        CtxBuffer.MEM_BASE_ADDR,
                        nativeMemory.nativeAddress().address());
                ctx.set(ValueLayout.JAVA_INT, CtxBuffer.MEMORY_PAGES, mem.pages());
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

    /**
     * Lazily replace module-defined GlobalInstance objects with NativeGlobalInstance
     * backed by the off-heap globalsBuffer. Called once on first native call,
     * after Instance.initialize() has created the original GlobalInstance objects.
     *
     * An imported global is moved onto the same buffer rather than copied into it,
     * so that what the module writes stays visible through the caller's own object.
     */
    private void initializeImportGlobals() {
        if (importGlobalsInitialized || globalCount == 0) {
            return;
        }
        importGlobalsInitialized = true;

        // Module-defined globals are already NativeGlobalInstance (created by globalFactory).
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
            if (!(global instanceof NativeGlobalInstance nativeGlobal)) {
                throw new WasmEngineException(foreignImportMessage("global", global));
            }
            if (nativeGlobal.isAdoptable()) {
                // Passed in by the host: adopt it, so what this module writes stays
                // visible through the caller's own object.
                nativeGlobal.bind(globalsBuffer, i);
                boundGlobals.add(nativeGlobal);
            } else {
                // Exported by another module, and already sitting where that
                // module's compiled code reads it. Its storage cannot move, so this
                // module starts from its current value.
                globalsBuffer.set(ValueLayout.JAVA_LONG, (long) i * 8, nativeGlobal.getValue());
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
        var tables = new NativeTable[tableCount];
        for (int i = 0; i < tableCount; i++) {
            var table = instance.table(i);
            if (!(table instanceof NativeTable nt)) {
                throw new WasmEngineException(foreignImportMessage("table", table));
            }
            tables[i] = nt;
        }

        if (tableCount > 0 && tablePtrsArray.byteSize() == 0) {
            this.tablePtrsArray = arena.allocate((long) tableCount * 8, 8);
            this.tableWriters = arena.allocate((long) tableCount * 4, 4).fill((byte) 0);
        }
        for (int i = 0; i < tableCount; i++) {
            tables[i].resolvePendingRefs(instance);
            tablePtrsArray.set(
                    ValueLayout.JAVA_LONG, (long) i * 8, tables[i].nativeBuffer().address());
        }
        this.nativeTables = tables;
        if (tableCount > 0) {
            ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.TABLE_PTRS, tablePtrsArray.address());
            ctxBuffer.set(
                    ValueLayout.JAVA_LONG, CtxBuffer.TABLE_WRITERS_PTR, tableWriters.address());
        }
        tablesInitialized = true;
    }

    /** Returns how many functions, imported and defined, this machine's function table holds. */
    int functionCount() {
        return (int) (funcTable.byteSize() / 8);
    }

    /** Package-private: used by NativeTable to resolve funcId → funcPtr across modules. */
    MemorySegment getFuncTable() {
        return funcTable;
    }

    /** Package-private: used by NativeTable to resolve funcId → canonicalTypeIdx across modules. */
    MemorySegment getFuncTypesArray() {
        return funcTypesArray;
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
        ctxBuffer.set(ValueLayout.JAVA_INT, CtxBuffer.TRAP_CODE, CtxBuffer.TRAP_HOST_EXCEPTION);
    }

    private static WasmEngineException trapException(int trapCode) {
        // TrapException, not the WasmEngineException parent: Instance catches
        // TrapException to report a trapping start function as uninstantiable.
        return switch (trapCode) {
            case CtxBuffer.TRAP_DIV_BY_ZERO -> new TrapException("integer divide by zero");
            case CtxBuffer.TRAP_INT_OVERFLOW -> new TrapException("integer overflow");
            case CtxBuffer.TRAP_UNREACHABLE -> new TrapException("unreachable");
            case CtxBuffer.TRAP_TRUNC_OVERFLOW -> new TrapException("integer overflow");
            case CtxBuffer.TRAP_TRUNC_NAN -> new TrapException("invalid conversion to integer");
            case CtxBuffer.TRAP_OOB -> new WasmRuntimeException("out of bounds memory access");
            case CtxBuffer.TRAP_CALL_STACK_EXHAUSTED -> new TrapException("call stack exhausted");
            case CtxBuffer.TRAP_TABLE_OOB -> new TrapException("out of bounds table access");
            case CtxBuffer.TRAP_UNDEFINED_ELEMENT -> new TrapException("undefined element");
            case CtxBuffer.TRAP_UNINITIALIZED_ELEMENT -> new TrapException("uninitialized element");
            case CtxBuffer.TRAP_INDIRECT_CALL_TYPE_MISMATCH ->
                    new TrapException("indirect call type mismatch");
            case CtxBuffer.TRAP_UNALIGNED_ATOMIC -> new TrapException("unaligned atomic");
            case CtxBuffer.TRAP_INTERRUPTED -> new WasmInterruptedException("Thread interrupted");
            default -> new WasmEngineException("trap: unknown code " + trapCode);
        };
    }

    // --- Main dispatch ---

    private ValueLayout valTypeToLayout(ValType type) {
        if (type.equals(ValType.I32)) {
            return ValueLayout.JAVA_INT;
        }
        if (type.equals(ValType.I64)) {
            return ValueLayout.JAVA_LONG;
        }
        if (type.equals(ValType.F32)) {
            return ValueLayout.JAVA_FLOAT;
        }
        if (type.equals(ValType.F64)) {
            return ValueLayout.JAVA_DOUBLE;
        }
        // Reference types (funcref, externref) are opaque i64
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return ValueLayout.JAVA_LONG;
        }
        throw new WasmEngineException("Unsupported type for native: " + type);
    }

    private Class<?> valTypeToJavaClass(ValType type) {
        if (type.equals(ValType.I32)) {
            return int.class;
        }
        if (type.equals(ValType.I64)) {
            return long.class;
        }
        if (type.equals(ValType.F32)) {
            return float.class;
        }
        if (type.equals(ValType.F64)) {
            return double.class;
        }
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return long.class;
        }
        throw new WasmEngineException("Unsupported type: " + type);
    }

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

        var handle = downcalls[funcId];
        if (handle == null) {
            throw new WasmEngineException("Function " + funcId + " not compiled");
        }

        boolean outermostCall = enterCall();
        // a nested instance shares the stack budget of the one running on this thread
        NativeMachine outer = null;
        if (outermostCall) {
            outer = RUNNING.get();
            RUNNING.set(this);
        }
        try {
            var funcType = (FunctionType) instance.type(instance.functionType(funcId));

            initializeImportGlobals();
            initializeNativeTables();

            // Re-anchor the stack guard only for a call that starts on this
            // stack. A host function calling back in has to keep measuring
            // against where the outer call began, or every level moves the
            // limit deeper and the guard stops firing.
            if (outermostCall) {
                ctxBuffer.set(
                        ValueLayout.JAVA_LONG,
                        CtxBuffer.STACK_LIMIT,
                        outer == null ? 0L : outer.stackLimit());
            }

            if (!memBaseInitialized) {
                var mem = instance.memory();
                if (mem instanceof NativeMemory nativeMemory) {
                    // an unscoped view, so calls don't acquire the memory's arena
                    cachedMemBase = MemorySegment.ofAddress(nativeMemory.nativeAddress().address());
                    ctxBuffer.set(
                            ValueLayout.JAVA_LONG,
                            CtxBuffer.MEM_BASE_ADDR,
                            cachedMemBase.address());
                    ctxBuffer.set(
                            ValueLayout.JAVA_LONG,
                            CtxBuffer.MEMORY_PAGES_PTR,
                            nativeMemory.pagesAddress().address());
                } else if (mem != null) {
                    throw new WasmEngineException(foreignImportMessage("memory", mem));
                } else {
                    cachedMemBase = MemorySegment.NULL;
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
                result = (long) handle.invokeExact(cachedMemBase, ctxBuffer, args);
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
                ctxBuffer.set(ValueLayout.JAVA_INT, CtxBuffer.TRAP_CODE, 0);
                sneakyThrow(ex);
            }

            // Check for traps (pre-checks write trap code to ctxBuffer)
            int trapCode = ctxBuffer.get(ValueLayout.JAVA_INT, CtxBuffer.TRAP_CODE);
            if (trapCode != 0) {
                ctxBuffer.set(ValueLayout.JAVA_INT, CtxBuffer.TRAP_CODE, 0);
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
                    long raw = argsBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.argOffset(i));
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
        return ctxBuffer.get(ValueLayout.JAVA_LONG, CtxBuffer.STACK_LIMIT);
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
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.INTERRUPT_FLAG, 1L);
    }

    private void clearInterrupt() {
        ctxBuffer.set(ValueLayout.JAVA_LONG, CtxBuffer.INTERRUPT_FLAG, 0L);
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
