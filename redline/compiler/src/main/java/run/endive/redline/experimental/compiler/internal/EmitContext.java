package run.endive.redline.experimental.compiler.internal;

import java.util.Map;
import run.endive.redline.experimental.api.internal.CtxBuffer;
import run.endive.redline.experimental.bridge.internal.CraneliftBridge;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.FunctionImport;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/**
 * Shared state passed to {@link NativeEmitters} methods during code emission.
 */
final class EmitContext {

    final CraneliftBridge bridge;
    final NativeValueStack valueStack;
    final WasmModule module;
    final int numImports;
    final FunctionType funcType;
    final int[] localVars;
    final int memBaseVar;
    final int ctxPtrVar;
    final Map<String, Integer> sigRefCache;
    final boolean multiReturn;
    final int[] canonicalTypeMap;

    EmitContext(
            CraneliftBridge bridge,
            NativeValueStack valueStack,
            WasmModule module,
            int numImports,
            FunctionType funcType,
            int[] localVars,
            int memBaseVar,
            int ctxPtrVar,
            Map<String, Integer> sigRefCache,
            boolean multiReturn,
            int[] canonicalTypeMap) {
        this.bridge = bridge;
        this.valueStack = valueStack;
        this.module = module;
        this.numImports = numImports;
        this.funcType = funcType;
        this.localVars = localVars;
        this.memBaseVar = memBaseVar;
        this.ctxPtrVar = ctxPtrVar;
        this.sigRefCache = sigRefCache;
        this.multiReturn = multiReturn;
        this.canonicalTypeMap = canonicalTypeMap;
    }

    // --- Helpers used by emitters ---

    int emitZero(ValType type) {
        if (type.equals(ValType.I32)) {
            return bridge.exports().emitIconst32(0);
        }
        if (type.equals(ValType.I64)) {
            return bridge.exports().emitIconst64(0, 0);
        }
        if (type.equals(ValType.F32)) {
            return bridge.exports().emitF32const(0);
        }
        if (type.equals(ValType.F64)) {
            return bridge.exports().emitF64const(0, 0);
        }
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return bridge.exports().emitIconst64(0, 0);
        }
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }

    static int valTypeToBridgeType(ValType type) {
        return CraneliftBridge.valTypeToBridgeType(type);
    }

    void emitReturnForFuncType() {
        if (funcType.returns().isEmpty()) {
            bridge.exports().emitReturnVoid();
        } else if (!multiReturn) {
            bridge.exports().emitReturn(emitZero(funcType.returns().get(0)));
        } else {
            // Multi-return: write zeros to argsBuffer, return dummy i64
            emitWriteReturnsToArgsBuffer(funcType.returns(), null);
            bridge.exports().emitReturn(bridge.exports().emitIconst64(0, 0));
        }
    }

    /**
     * Write return values to argsBuffer (widened to i64).
     * If vals is null, writes zeros.
     */
    void emitWriteReturnsToArgsBuffer(java.util.List<ValType> types, int[] vals) {
        int zero = bridge.exports().emitIconst32(0);
        int argsPtr =
                bridge.exports()
                        .emitLoadI64(bridge.exports().useVar(ctxPtrVar), zero, CtxBuffer.ARGS_PTR);
        for (int i = 0; i < types.size(); i++) {
            int val = (vals != null) ? vals[i] : emitZero(types.get(i));
            int widened = widenToI64ForType(val, types.get(i));
            bridge.exports().emitStoreI64(argsPtr, zero, widened, CtxBuffer.argOffset(i));
        }
    }

    FunctionType resolveCallTargetType(int funcId) {
        if (funcId < numImports) {
            int idx = 0;
            for (var imp :
                    module.importSection().stream().collect(java.util.stream.Collectors.toList())) {
                if (imp.importType() == ExternalType.FUNCTION) {
                    if (idx == funcId) {
                        int typeIdx = ((FunctionImport) imp).typeIndex();
                        return (FunctionType) module.typeSection().getType(typeIdx);
                    }
                    idx++;
                }
            }
            throw new IllegalArgumentException("Import function not found: " + funcId);
        }
        int bodyIdx = funcId - numImports;
        int typeIdx = module.functionSection().getFunctionType(bodyIdx);
        return (FunctionType) module.typeSection().getType(typeIdx);
    }

    ValType resolveGlobalType(int globalIdx) {
        int importGlobalIdx = 0;
        for (var imp :
                module.importSection().stream().collect(java.util.stream.Collectors.toList())) {
            if (imp.importType() == ExternalType.GLOBAL) {
                if (importGlobalIdx == globalIdx) {
                    return ((run.endive.wasm.types.GlobalImport) imp).type();
                }
                importGlobalIdx++;
            }
        }
        int moduleGlobalIdx = globalIdx - importGlobalIdx;
        return module.globalSection().getGlobal(moduleGlobalIdx).valueType();
    }

    int widenToI64(int valId, ValType type) {
        if (type.equals(ValType.I32)) {
            return bridge.exports().emitUextendI64(valId);
        }
        return valId;
    }

    int narrowFromI64(int valId, ValType type) {
        if (type.equals(ValType.I32)) {
            return bridge.exports().emitIreduceI32(valId);
        }
        return valId;
    }

    int widenToI64ForType(int valId, ValType type) {
        if (type.equals(ValType.I32)) {
            return bridge.exports().emitUextendI64(valId);
        }
        if (type.equals(ValType.F32)) {
            int bits = bridge.exports().emitBitcastF32ToI32(valId);
            return bridge.exports().emitUextendI64(bits);
        }
        if (type.equals(ValType.F64)) {
            return bridge.exports().emitBitcastF64ToI64(valId);
        }
        return valId; // I64
    }

    int narrowFromI64ForType(int valId, ValType type) {
        if (type.equals(ValType.I32)) {
            return bridge.exports().emitIreduceI32(valId);
        }
        if (type.equals(ValType.F32)) {
            int narrow = bridge.exports().emitIreduceI32(valId);
            return bridge.exports().emitBitcastI32ToF32(narrow);
        }
        if (type.equals(ValType.F64)) {
            return bridge.exports().emitBitcastI64ToF64(valId);
        }
        return valId; // I64
    }

    int getOrCreateSigRef(FunctionType ft) {
        String key = ft.toString();
        Integer cached = sigRefCache.get(key);
        if (cached != null) {
            return cached;
        }

        bridge.exports().beginSig();
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64); // memBase
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64); // ctxPtr
        for (ValType param : ft.params()) {
            bridge.exports().sigAddParam(valTypeToBridgeType(param));
        }
        for (ValType ret : ft.returns()) {
            bridge.exports().sigAddReturn(valTypeToBridgeType(ret));
        }
        int sigRef = bridge.exports().endSig();
        sigRefCache.put(key, sigRef);
        return sigRef;
    }

    int getOrCreateMultiReturnSigRef(FunctionType ft) {
        String key = "__mr__" + ft.toString();
        Integer cached = sigRefCache.get(key);
        if (cached != null) {
            return cached;
        }

        bridge.exports().beginSig();
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64); // memBase
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64); // ctxPtr
        for (ValType param : ft.params()) {
            bridge.exports().sigAddParam(valTypeToBridgeType(param));
        }
        // Multi-return: single i64 dummy return (actual values in argsBuffer)
        bridge.exports().sigAddReturn(CraneliftBridge.TYPE_I64);
        int sigRef = bridge.exports().endSig();
        sigRefCache.put(key, sigRef);
        return sigRef;
    }

    int getOrCreateTrampolineSigRef() {
        String key = "__trampoline__";
        Integer cached = sigRefCache.get(key);
        if (cached != null) {
            return cached;
        }

        bridge.exports().beginSig();
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64);
        bridge.exports().sigAddReturn(CraneliftBridge.TYPE_I64);
        int sigRef = bridge.exports().endSig();
        sigRefCache.put(key, sigRef);
        return sigRef;
    }

    /** Signature for memmove/memset: (i64, i64, i64) -> i64. */
    int getOrCreateMemopSigRef() {
        String key = "__memop__";
        Integer cached = sigRefCache.get(key);
        if (cached != null) {
            return cached;
        }

        bridge.exports().beginSig();
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64); // dst ptr
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64); // src ptr / value
        bridge.exports().sigAddParam(CraneliftBridge.TYPE_I64); // size
        bridge.exports().sigAddReturn(CraneliftBridge.TYPE_I64);
        int sigRef = bridge.exports().endSig();
        sigRefCache.put(key, sigRef);
        return sigRef;
    }
}
