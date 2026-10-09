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
import java.util.ArrayList;
import java.util.List;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.internal.CtxBuffer;
import run.endive.redline.experimental.api.internal.InterruptWatchdog;
import run.endive.redline.experimental.api.internal.NativeCodeCheck;
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
    private long tablePtrsArrayAddr;
    private JffiNativeTable[] nativeTables;
    private boolean[] ownsTable;
    private boolean tablesInitialized;
    private boolean ownsMemory;
    private boolean closed;
    private final int numImports;
    private final int globalCount;
    private boolean importGlobalsInitialized;
    private long cachedMemBase;
    private boolean memBaseInitialized;
    private JffiNativeMemory nativeMemory;
    private volatile Throwable pendingException;
    private int callDepth;
    private final InterruptWatchdog.InterruptSink interruptFlag = this::raiseInterruptFlag;

    // Keep closure handles alive to prevent GC
    private final Closure.Handle trampolineHandle;
    private final Closure.Handle memGrowHandle;
    private final Closure.Handle[] importHandles;

    public JffiNativeMachine(
            Instance instance,
            List<JffiNativeTable> sharedTables,
            long sharedGlobalsBufferAddr,
            NativeCode precompiledCode) {
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
        ctxBufferAddr = MEM.allocateMemory(CTX_SIZE, true);

        // Allocate function pointer table (one i64 per function)
        this.funcTableSize = (long) totalFuncs * 8;
        funcTableAddr = MEM.allocateMemory(funcTableSize, true);

        // Globals buffer from factory
        this.globalsBufferAddr = sharedGlobalsBufferAddr;
        this.globalCount =
                (int)
                                module.importSection().stream()
                                        .filter(
                                                i ->
                                                        i.importType()
                                                                == run.endive.wasm.types
                                                                        .ExternalType.GLOBAL)
                                        .count()
                        + (module.globalSection() != null
                                ? module.globalSection().globalCount()
                                : 0);

        // Allocate args buffer
        this.argsBufferAddr = MEM.allocateMemory((long) CtxBuffer.ARGS_BUFFER_CAPACITY * 8, true);

        // Allocate funcTypes array with canonical type indices
        int[] canonicalTypeMap = TypeMapUtils.buildCanonicalTypeMap(module);
        this.funcTypesArraySize = (long) totalFuncs * 4;
        this.funcTypesArrayAddr = MEM.allocateMemory(funcTypesArraySize, true);
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
        this.trampolineHandle = createTrampolineStub();
        this.memGrowHandle = createMemGrowStub();
        for (int funcId = 0; funcId < numImports; funcId++) {
            var funcType = instance.imports().function(funcId).functionType();
            funcTypes[funcId] = funcType;
            importHandles[funcId] = createImportStub(funcId, funcType);
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

        byte[] image = code.image();

        // Allocate executable code region via PageManager
        int osPageSize = (int) PM.pageSize();
        this.codeRegionOsPages = (Math.max(image.length, 1) + osPageSize - 1) / osPageSize;
        this.codeRegionAddr =
                PM.allocatePages(codeRegionOsPages, PageManager.PROT_READ | PageManager.PROT_WRITE);
        if (codeRegionAddr == 0 || codeRegionAddr == -1) {
            throw new WasmEngineException("Failed to allocate executable code pages");
        }

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
    }

    @Override
    public void close() {
        if (closed) {
            // Every free below is a native one, so a second close would be a
            // double free rather than a no-op.
            return;
        }
        closed = true;
        if (ownsTable != null) {
            for (int i = 0; i < ownsTable.length; i++) {
                if (ownsTable[i]) {
                    nativeTables[i].free();
                }
            }
        }
        if (nativeMemory != null && ownsMemory) {
            nativeMemory.close();
        }
        if (tablePtrsArrayAddr != 0) {
            MEM.freeMemory(tablePtrsArrayAddr);
        }
        trampolineHandle.dispose();
        memGrowHandle.dispose();
        for (Closure.Handle h : importHandles) {
            if (h != null) {
                h.dispose();
            }
        }
        if (codeRegionOsPages > 0 && codeRegionAddr != 0) {
            PM.freePages(codeRegionAddr, codeRegionOsPages);
        }
        MEM.freeMemory(ctxBufferAddr);
        MEM.freeMemory(funcTableAddr);
        MEM.freeMemory(argsBufferAddr);
        MEM.freeMemory(funcTypesArrayAddr);
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

    private long callIndirectTrampoline(long ctxAddr) {
        try {
            int argCount = MEM.getInt(ctxAddr + CtxBuffer.ARG_COUNT);

            // Negative argCount = table operation sentinel
            if (argCount < 0) {
                return handleTableOperation(argCount);
            }
            throw new WasmEngineException("Unexpected trampoline call: argCount " + argCount);
        } catch (Throwable t) {
            recordHostException(t);
            return 0L;
        }
    }

    private long handleTableOperation(int opCode) {
        switch (opCode) {
            case -1:
                { // table grow fill
                    int oldSize = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int newSize = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int fillValue = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long tableAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int tblIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    boolean externRef = nativeTables[tblIdx].isExternRef();
                    for (int i = oldSize; i < newSize; i++) {
                        writeTableEntry(tableAddr, i, fillValue, externRef);
                    }
                    nativeTables[tblIdx].limits().grow(newSize - oldSize);
                    break;
                }
            case -2:
                { // table fill
                    int offset = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    int end = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int fillValue = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    long tableAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int tblIdx = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    boolean externRef = nativeTables[tblIdx].isExternRef();
                    for (int i = offset; i < end; i++) {
                        writeTableEntry(tableAddr, i, fillValue, externRef);
                    }
                    break;
                }
            case -3:
                { // table copy (16-byte entries)
                    long srcAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(0));
                    long dstAddr = MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(1));
                    int srcOff = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(2));
                    int dstOff = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(3));
                    int size = (int) MEM.getLong(argsBufferAddr + CtxBuffer.argOffset(4));
                    if (dstOff <= srcOff) {
                        for (int i = 0; i < size; i++) {
                            copyTableEntry(srcAddr, srcOff + i, dstAddr, dstOff + i);
                        }
                    } else {
                        for (int i = size - 1; i >= 0; i--) {
                            copyTableEntry(srcAddr, srcOff + i, dstAddr, dstOff + i);
                        }
                    }
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

    private void writeTableEntry(long tableAddr, int index, int funcId, boolean isExternRef) {
        long base =
                tableAddr
                        + CtxBuffer.TABLE_ENTRIES_OFFSET
                        + (long) index * CtxBuffer.TABLE_ENTRY_SIZE;
        if (funcId == Value.REF_NULL_VALUE) {
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, Value.REF_NULL_VALUE);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        } else if (isExternRef) {
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, 0);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, 0L);
        } else {
            long funcPtr = MEM.getLong(funcTableAddr + (long) funcId * 8);
            int typeIdx = MEM.getInt(funcTypesArrayAddr + (long) funcId * 4);
            MEM.putInt(base + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
            MEM.putInt(base + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
            MEM.putLong(base + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
        }
    }

    private static void copyTableEntry(long srcAddr, int srcIdx, long dstAddr, int dstIdx) {
        long srcBase =
                srcAddr
                        + CtxBuffer.TABLE_ENTRIES_OFFSET
                        + (long) srcIdx * CtxBuffer.TABLE_ENTRY_SIZE;
        long dstBase =
                dstAddr
                        + CtxBuffer.TABLE_ENTRIES_OFFSET
                        + (long) dstIdx * CtxBuffer.TABLE_ENTRY_SIZE;
        int typeIdx = MEM.getInt(srcBase + CtxBuffer.ENTRY_TYPE_IDX_OFFSET);
        int funcId = MEM.getInt(srcBase + CtxBuffer.ENTRY_FUNC_ID_OFFSET);
        long funcPtr = MEM.getLong(srcBase + CtxBuffer.ENTRY_FUNC_PTR_OFFSET);
        MEM.putInt(dstBase + CtxBuffer.ENTRY_TYPE_IDX_OFFSET, typeIdx);
        MEM.putInt(dstBase + CtxBuffer.ENTRY_FUNC_ID_OFFSET, funcId);
        MEM.putLong(dstBase + CtxBuffer.ENTRY_FUNC_PTR_OFFSET, funcPtr);
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
            if (nativeGlobal.isStandalone()) {
                // Passed in by the host: adopt it, so what this module writes stays
                // visible through the caller's own object.
                nativeGlobal.rebind(globalsBufferAddr, i);
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
        tablesInitialized = true;

        var module = instance.module();
        int importedTableCount = instance.imports().tableCount();
        int definedTableCount = module.tableSection().tableCount();
        int tableCount = importedTableCount + definedTableCount;

        if (tableCount == 0) {
            this.nativeTables = new JffiNativeTable[0];
            return;
        }

        this.nativeTables = new JffiNativeTable[tableCount];
        boolean[] owned = new boolean[tableCount];
        this.ownsTable = owned;
        this.tablePtrsArrayAddr = MEM.allocateMemory((long) tableCount * 8, true);

        for (int i = 0; i < tableCount; i++) {
            var table = instance.table(i);
            if (table instanceof JffiNativeTable) {
                var nt = (JffiNativeTable) table;
                nt.resolvePendingRefs(instance);
                nativeTables[i] = nt;
                // A table this module defines came from our factory and dies with
                // the instance. An imported one belongs to whoever created it.
                owned[i] = i >= importedTableCount;
            } else {
                throw new WasmEngineException(foreignImportMessage("table", table));
            }

            MEM.putLong(tablePtrsArrayAddr + (long) i * 8, nativeTables[i].nativeBufferAddress());
        }

        MEM.putLong(ctxBufferAddr + CtxBuffer.TABLE_PTRS, tablePtrsArrayAddr);
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
        if (funcId < numImports) {
            // Host import — delegate directly
            var imprt = instance.imports().function(funcId);
            return imprt.handle().apply(instance, args);
        }

        var funcType = funcTypes[funcId];
        long funcAddr = MEM.getLong(funcTableAddr + (long) funcId * 8);

        try {
            boolean outermostCall = callDepth++ == 0;
            initializeImportGlobals();
            initializeNativeTables();

            // Re-anchor the stack guard only for a call that starts on this
            // stack. A host function calling back in has to keep measuring
            // against where the outer call began, or every level moves the
            // limit deeper and the guard stops firing.
            if (outermostCall) {
                MEM.putLong(ctxBufferAddr + CtxBuffer.STACK_LIMIT, 0L);
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
            callDepth--;
            // Prevent the JIT from considering this machine unreachable during
            // the native call, which would let GC collect and close() free
            // native memory while code is executing.
            Reference.reachabilityFence(this);
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
