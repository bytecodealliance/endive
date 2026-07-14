package run.endive.redline.experimental.bridge;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

public final class CraneliftBridge implements AutoCloseable {

    private final Instance instance;
    private final WasiPreview1 wasi;
    private final CraneliftBridge_ModuleExports exports;

    public CraneliftBridge() {
        var wasiOpts = WasiOptions.builder().build();
        wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();

        instance =
                Instance.builder(Cranelift.load())
                        .withImportValues(imports)
                        .withMachineFactory(Cranelift::create)
                        .build();
        exports = new CraneliftBridge_ModuleExports(instance);
    }

    @Override
    public void close() {
        instance.close();
        wasi.close();
    }

    public static final int TYPE_I32 = 0;
    public static final int TYPE_I64 = 1;
    public static final int TYPE_F32 = 2;
    public static final int TYPE_F64 = 3;

    public static int valTypeToBridgeType(ValType type) {
        if (type.equals(ValType.I32)) {
            return TYPE_I32;
        }
        if (type.equals(ValType.I64)) {
            return TYPE_I64;
        }
        if (type.equals(ValType.F32)) {
            return TYPE_F32;
        }
        if (type.equals(ValType.F64)) {
            return TYPE_F64;
        }
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return TYPE_I64;
        }
        throw new UnsupportedOperationException("Unsupported ValType for native: " + type);
    }

    public void init(String target) {
        byte[] bytes = target.getBytes(StandardCharsets.UTF_8);
        int ptr = exports.wasmMalloc(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            exports.memory().writeByte(ptr + i, bytes[i]);
        }
        exports.init(ptr, bytes.length);
        exports.wasmFree(ptr, bytes.length);
    }

    public CraneliftBridge_ModuleExports exports() {
        return exports;
    }

    public byte[] compile() {
        exports.compile();
        int codePtr = exports.getCodePtr();
        int codeLen = exports.getCodeLen();
        return exports.memory().readBytes(codePtr, codeLen);
    }

    private void buildTrampolineSig(FunctionType funcType) {
        exports.beginTrampolineSig();
        exports.trampolineSigAddParam(TYPE_I64); // memBase
        exports.trampolineSigAddParam(TYPE_I64); // ctxPtr
        for (ValType param : funcType.params()) {
            exports.trampolineSigAddParam(valTypeToBridgeType(param));
        }
        if (funcType.returns().size() > 1) {
            exports.trampolineSigAddReturn(TYPE_I64);
        } else {
            for (ValType ret : funcType.returns()) {
                exports.trampolineSigAddReturn(valTypeToBridgeType(ret));
            }
        }
    }

    private byte[] readCompiledCode() {
        int ptr = exports.getCodePtr();
        int len = exports.getCodeLen();
        return exports.memory().readBytes(ptr, len);
    }

    private byte[] compileImportTrampolineRaw(long stubAddr) {
        exports.compileImportTrampoline((int) (stubAddr & 0xFFFFFFFFL), (int) (stubAddr >>> 32));
        return readCompiledCode();
    }

    public byte[] compileEntryTrampoline(FunctionType funcType) {
        buildTrampolineSig(funcType);
        exports.compileEntryTrampoline();
        return readCompiledCode();
    }

    public byte[] compileImportTrampoline(FunctionType funcType, long stubAddr) {
        buildTrampolineSig(funcType);
        return compileImportTrampolineRaw(stubAddr);
    }

    public byte[] compileStubTrampoline(long stubAddr, int[] paramTypes, int[] returnTypes) {
        exports.beginTrampolineSig();
        for (int p : paramTypes) {
            exports.trampolineSigAddParam(p);
        }
        for (int r : returnTypes) {
            exports.trampolineSigAddReturn(r);
        }
        return compileImportTrampolineRaw(stubAddr);
    }

    public static final class CompiledTrampolines {
        private final Map<FunctionType, byte[]> entryTrampolines;
        private final byte[][] importTrampolines;
        private final byte[] trampolineStubTramp;
        private final byte[] memGrowStubTramp;
        private final byte[] memmoveTramp;
        private final byte[] memsetTramp;

        CompiledTrampolines(
                Map<FunctionType, byte[]> entryTrampolines,
                byte[][] importTrampolines,
                byte[] trampolineStubTramp,
                byte[] memGrowStubTramp,
                byte[] memmoveTramp,
                byte[] memsetTramp) {
            this.entryTrampolines = entryTrampolines;
            this.importTrampolines = importTrampolines;
            this.trampolineStubTramp = trampolineStubTramp;
            this.memGrowStubTramp = memGrowStubTramp;
            this.memmoveTramp = memmoveTramp;
            this.memsetTramp = memsetTramp;
        }

        public Map<FunctionType, byte[]> entryTrampolines() {
            return entryTrampolines;
        }

        public byte[][] importTrampolines() {
            return importTrampolines;
        }

        public byte[] trampolineStubTramp() {
            return trampolineStubTramp;
        }

        public byte[] memGrowStubTramp() {
            return memGrowStubTramp;
        }

        public byte[] memmoveTramp() {
            return memmoveTramp;
        }

        public byte[] memsetTramp() {
            return memsetTramp;
        }

        public long totalSize() {
            long size = 0;
            for (byte[] code : entryTrampolines.values()) {
                size += align(code.length, 16);
            }
            for (byte[] code : importTrampolines) {
                size += align(code.length, 16);
            }
            size += align(trampolineStubTramp.length, 16);
            size += align(memGrowStubTramp.length, 16);
            size += align(memmoveTramp.length, 16);
            size += align(memsetTramp.length, 16);
            return size;
        }
    }

    public CompiledTrampolines compileTrampolines(
            byte[][] compiledCode,
            FunctionType[] funcTypesByBody,
            FunctionType[] importTypes,
            long[] importStubAddrs,
            long trampolineStubAddr,
            long memGrowStubAddr,
            long memmoveAddr,
            long memsetAddr) {

        Map<FunctionType, byte[]> entryTrampolineCode = new HashMap<>();
        for (int i = 0; i < compiledCode.length; i++) {
            if (compiledCode[i] != null && !entryTrampolineCode.containsKey(funcTypesByBody[i])) {
                entryTrampolineCode.put(
                        funcTypesByBody[i], compileEntryTrampoline(funcTypesByBody[i]));
            }
        }

        byte[][] importTrampolineCode = new byte[importTypes.length][];
        for (int i = 0; i < importTypes.length; i++) {
            importTrampolineCode[i] = compileImportTrampoline(importTypes[i], importStubAddrs[i]);
        }

        int[] i64Param = {TYPE_I64};
        int[] i64Return = {TYPE_I64};
        int[] i64x3Param = {TYPE_I64, TYPE_I64, TYPE_I64};

        return new CompiledTrampolines(
                entryTrampolineCode,
                importTrampolineCode,
                compileStubTrampoline(trampolineStubAddr, i64Param, i64Return),
                compileStubTrampoline(memGrowStubAddr, i64Param, i64Return),
                compileStubTrampoline(memmoveAddr, i64x3Param, i64Return),
                compileStubTrampoline(memsetAddr, i64x3Param, i64Return));
    }

    public static long align(long value, long alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }
}
