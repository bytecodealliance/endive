package run.endive.redline.experimental.runner;

import java.lang.foreign.Arena;
import run.endive.redline.experimental.api.NativeCode;
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

public final class NativeMachineFactory {

    private final NativeCode precompiledCode;
    // the arena of the instance being built, which its tables are allocated in
    private Arena instanceArena;

    private NativeMachineFactory(NativeCode precompiledCode) {
        this.precompiledCode = precompiledCode;
    }

    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public TableInstance createTable(Table table, int initValue) {
        // the instance creates its tables right after its machine, in the same constructor
        return new NativeTable(table, initValue, instanceArena);
    }

    /** The caller owns the table and closes it once no instance imports it. */
    public static TableInstance createImportTable(Table table, int initValue) {
        return NativeTable.standalone(table, initValue);
    }

    public GlobalInstance createGlobal(
            long value, long highValue, ValType type, MutabilityType mutability) {
        return NativeGlobalInstance.defined(value, type, mutability);
    }

    public static GlobalInstance createImportGlobal(
            long value, ValType type, MutabilityType mutability) {
        return NativeGlobalInstance.hostCreated(value, type, mutability);
    }

    /** The caller owns the memory and closes it once no instance imports it. */
    public static Memory createMemory(MemoryLimits limits) {
        return new NativeMemory(limits);
    }

    public Machine compile(Instance instance) {
        var arena = Arena.ofShared();
        try {
            this.instanceArena = arena;
            return new NativeMachine(instance, arena, precompiledCode);
        } catch (RuntimeException | Error e) {
            // frees what the machine allocated before it failed
            arena.close();
            throw e;
        }
    }

    public static final class Builder {

        private final WasmModule module;
        private NativeCode precompiledCode;
        private ImportValues importValues;
        private MemoryLimits memoryLimits;
        private boolean start = true;
        private boolean initialize = true;

        Builder(WasmModule module) {
            this.module = module;
        }

        public Builder withPrecompiledCode(NativeCode precompiledCode) {
            this.precompiledCode = precompiledCode;
            return this;
        }

        public Builder withImportValues(ImportValues importValues) {
            this.importValues = importValues;
            return this;
        }

        public Builder withMemoryLimits(MemoryLimits limits) {
            var memories = module.memorySection();
            if (memories.isPresent()
                    && memories.get().memoryCount() > 0
                    && memories.get().getMemory(0).limits().shared() != limits.shared()) {
                // the native code was compiled for one or the other
                throw new IllegalArgumentException(
                        "The memory limits must keep the module's memory "
                                + (limits.shared() ? "unshared" : "shared"));
            }
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
            var factory = new NativeMachineFactory(precompiledCode);
            return Instance.builder(module)
                    .withMachineFactory(factory::compile)
                    .withTableFactory(factory::createTable)
                    .withGlobalFactory(factory::createGlobal)
                    .withMemoryFactory(NativeMachineFactory::createMemory)
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
