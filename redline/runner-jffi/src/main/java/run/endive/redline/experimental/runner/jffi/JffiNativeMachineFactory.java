package run.endive.redline.experimental.runner.jffi;

import com.kenai.jffi.MemoryIO;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import run.endive.redline.experimental.runner.jffi.internal.JffiNativeGlobalInstance;
import run.endive.redline.experimental.runner.jffi.internal.JffiNativeMachine;
import run.endive.redline.experimental.runner.jffi.internal.JffiNativeMemory;
import run.endive.redline.experimental.runner.jffi.internal.JffiNativeTable;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.ValType;

public final class JffiNativeMachineFactory {

    private static final MemoryIO MEM = MemoryIO.getInstance();

    private final WasmModule module;
    private final byte[][] precompiledCode;
    private final Function<WasmModule, byte[][]> compilerFunction;
    private final List<JffiNativeTable> nativeTables = new ArrayList<>();
    private long globalsBufferAddr;
    private int globalIndex;
    private JffiNativeMachine nativeMachine;

    private JffiNativeMachineFactory(
            WasmModule module,
            byte[][] precompiledCode,
            Function<WasmModule, byte[][]> compilerFunction) {
        this.module = module;
        this.precompiledCode = precompiledCode;
        this.compilerFunction = compilerFunction;

        int importGlobalCount =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == run.endive.wasm.types.ExternalType
                                                                .GLOBAL)
                                .count();
        int definedGlobalCount =
                module.globalSection() != null ? module.globalSection().globalCount() : 0;
        int totalGlobals = importGlobalCount + definedGlobalCount;
        this.globalsBufferAddr =
                totalGlobals > 0 ? MEM.allocateMemory((long) totalGlobals * 8, true) : 0L;
        this.globalIndex = importGlobalCount;
    }

    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public TableInstance createTable(Table table, int initValue) {
        var nativeTable = new JffiNativeTable(table);
        nativeTables.add(nativeTable);
        return nativeTable;
    }

    public static TableInstance createImportTable(Table table, int initValue) {
        return new JffiNativeTable(table);
    }

    public GlobalInstance createGlobal(
            long value, long highValue, ValType type, MutabilityType mutability) {
        return new JffiNativeGlobalInstance(
                globalsBufferAddr, globalIndex++, value, type, mutability);
    }

    public static GlobalInstance createImportGlobal(
            long value, ValType type, MutabilityType mutability) {
        long addr = MEM.allocateMemory(8, true);
        return new JffiNativeGlobalInstance(addr, 0, value, type, mutability);
    }

    public static Memory createMemory(MemoryLimits limits) {
        return new JffiNativeMemory(limits);
    }

    public Machine compile(Instance instance) {
        int importGlobalCount =
                (int)
                        module.importSection().stream()
                                .filter(
                                        i ->
                                                i.importType()
                                                        == run.endive.wasm.types.ExternalType
                                                                .GLOBAL)
                                .count();
        this.globalIndex = importGlobalCount;
        this.nativeTables.clear();
        this.nativeMachine =
                new JffiNativeMachine(
                        instance,
                        nativeTables,
                        globalsBufferAddr,
                        precompiledCode,
                        compilerFunction);
        return nativeMachine;
    }

    public static final class Builder {

        private final WasmModule module;
        private byte[][] precompiledCode;
        private Function<WasmModule, byte[][]> compilerFunction;
        private ImportValues importValues;
        private MemoryLimits memoryLimits;
        private boolean start = true;
        private boolean initialize = true;

        Builder(WasmModule module) {
            this.module = module;
        }

        public Builder withPrecompiledCode(byte[][] precompiledCode) {
            this.precompiledCode = precompiledCode;
            return this;
        }

        public Builder withCompilerFunction(Function<WasmModule, byte[][]> compilerFunction) {
            this.compilerFunction = compilerFunction;
            return this;
        }

        public Builder withImportValues(ImportValues importValues) {
            this.importValues = importValues;
            return this;
        }

        public Builder withMemoryLimits(MemoryLimits limits) {
            this.memoryLimits = limits;
            return this;
        }

        public Builder withStart(boolean start) {
            this.start = start;
            return this;
        }

        public Builder withInitialize(boolean init) {
            this.initialize = init;
            return this;
        }

        public Instance.Builder toInstanceBuilder() {
            var factory = new JffiNativeMachineFactory(module, precompiledCode, compilerFunction);
            return Instance.builder(module)
                    .withMachineFactory(factory::compile)
                    .withTableFactory(factory::createTable)
                    .withGlobalFactory(factory::createGlobal)
                    .withMemoryFactory(JffiNativeMachineFactory::createMemory)
                    .withStart(start)
                    .withInitialize(initialize);
        }

        public Instance build() {
            var instanceBuilder = toInstanceBuilder();
            if (importValues != null) {
                instanceBuilder.withImportValues(importValues);
            }
            if (memoryLimits != null) {
                instanceBuilder.withMemoryLimits(memoryLimits);
            }
            return instanceBuilder.build();
        }
    }
}
