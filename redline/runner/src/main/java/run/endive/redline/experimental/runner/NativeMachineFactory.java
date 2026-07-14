package run.endive.redline.experimental.runner;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import run.endive.redline.experimental.api.Interruptible;
import run.endive.redline.experimental.runner.internal.NativeGlobalInstance;
import run.endive.redline.experimental.runner.internal.NativeMachine;
import run.endive.redline.experimental.runner.internal.NativeMemory;
import run.endive.redline.experimental.runner.internal.NativeTable;
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

public final class NativeMachineFactory implements AutoCloseable, Interruptible {

    private final Arena arena = Arena.ofShared();
    private final WasmModule module;
    private final byte[][] precompiledCode;
    private final Function<WasmModule, byte[][]> compilerFunction;
    private final List<NativeTable> nativeTables = new ArrayList<>();
    private MemorySegment globalsBuffer;
    private int globalIndex;
    private NativeMachine nativeMachine;

    private NativeMachineFactory(
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
        this.globalsBuffer =
                totalGlobals > 0 ? arena.allocate((long) totalGlobals * 8, 8) : MemorySegment.NULL;
        this.globalIndex = importGlobalCount;
    }

    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public TableInstance createTable(Table table, int initValue) {
        var nativeTable = new NativeTable(table, arena);
        nativeTables.add(nativeTable);
        return nativeTable;
    }

    public GlobalInstance createGlobal(
            long value, long highValue, ValType type, MutabilityType mutability) {
        return new NativeGlobalInstance(globalsBuffer, globalIndex++, value, type, mutability);
    }

    public static Memory createMemory(MemoryLimits limits) {
        return new NativeMemory(limits);
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
                new NativeMachine(
                        instance,
                        arena,
                        nativeTables,
                        globalsBuffer,
                        precompiledCode,
                        compilerFunction);
        return nativeMachine;
    }

    @Override
    public void requestInterrupt() {
        if (nativeMachine != null) {
            nativeMachine.requestInterrupt();
        }
    }

    @Override
    public void clearInterrupt() {
        if (nativeMachine != null) {
            nativeMachine.clearInterrupt();
        }
    }

    @Override
    public void close() {
        if (nativeMachine != null) {
            nativeMachine.close();
        }
        try {
            arena.close();
        } catch (IllegalStateException e) {
            // may already be closed by NativeMachine
        }
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

        public Instance build() {
            var factory = new NativeMachineFactory(module, precompiledCode, compilerFunction);
            var instanceBuilder =
                    Instance.builder(module)
                            .withMachineFactory(factory::compile)
                            .withTableFactory(factory::createTable)
                            .withGlobalFactory(factory::createGlobal)
                            .withMemoryFactory(NativeMachineFactory::createMemory)
                            .withStart(start)
                            .withInitialize(initialize);
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
