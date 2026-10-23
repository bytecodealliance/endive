package run.endive.redline.experimental.runner.jffi;

import run.endive.redline.experimental.api.NativeCode;
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

    private final NativeCode precompiledCode;

    private JffiNativeMachineFactory(NativeCode precompiledCode) {
        this.precompiledCode = precompiledCode;
    }

    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public TableInstance createTable(Table table, int initValue) {
        return new JffiNativeTable(table, initValue);
    }

    /** The caller owns the table and closes it once no instance imports it. */
    public static TableInstance createImportTable(Table table, int initValue) {
        return new JffiNativeTable(table, initValue);
    }

    public GlobalInstance createGlobal(
            long value, long highValue, ValType type, MutabilityType mutability) {
        return JffiNativeGlobalInstance.defined(value, type, mutability);
    }

    public static GlobalInstance createImportGlobal(
            long value, ValType type, MutabilityType mutability) {
        return JffiNativeGlobalInstance.hostCreated(value, type, mutability);
    }

    /** The caller owns the memory and closes it once no instance imports it. */
    public static Memory createMemory(MemoryLimits limits) {
        return new JffiNativeMemory(limits);
    }

    public Machine compile(Instance instance) {
        return JffiNativeMachine.create(instance, precompiledCode);
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
            var factory = new JffiNativeMachineFactory(precompiledCode);
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
