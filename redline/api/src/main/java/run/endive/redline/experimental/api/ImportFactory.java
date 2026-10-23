package run.endive.redline.experimental.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import run.endive.redline.experimental.api.internal.Borrowers;
import run.endive.redline.experimental.api.internal.NativeResource;
import run.endive.runtime.ByteBufferMemory;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.Value;

/**
 * Creates the memories, tables and globals a module imports, for whichever backend
 * is running. Obtained from the generated module's {@code imports()}.
 *
 * <p>It owns the native memories and tables it creates: close it after the instances using them.
 */
public final class ImportFactory implements AutoCloseable {

    private final NativeMachineFactoryProvider provider;
    private final List<NativeResource> created = new ArrayList<>();
    private boolean closed;

    private ImportFactory(NativeMachineFactoryProvider provider) {
        this.provider = provider;
    }

    /**
     * Picks the backend for a module holding this native code, or none. Called by the
     * generated {@code imports()}.
     */
    public static ImportFactory forNativeCode(NativeCode nativeCode) {
        if (nativeCode == null) {
            return forBytecode();
        }
        return NativeMachineFactoryProvider.discover()
                .map(ImportFactory::forNative)
                .orElseGet(ImportFactory::forBytecode);
    }

    /** Builds imports the given native backend can use. */
    public static ImportFactory forNative(NativeMachineFactoryProvider provider) {
        return new ImportFactory(Objects.requireNonNull(provider, "provider"));
    }

    /** Builds the ordinary runtime imports, for when there is no native backend. */
    public static ImportFactory forBytecode() {
        return new ImportFactory(null);
    }

    /** Whether these imports are being built for natively compiled code. */
    public boolean isNative() {
        return provider != null;
    }

    public Memory memory(MemoryLimits limits) {
        if (provider != null) {
            return own(() -> provider.createMemory(limits));
        }
        return new ByteBufferMemory(limits);
    }

    public TableInstance table(Table table, int initValue) {
        if (provider != null) {
            return own(() -> provider.createImportTable(table, initValue));
        }
        return new TableInstance(table, initValue);
    }

    public GlobalInstance global(Value value, MutabilityType mutability) {
        if (provider != null) {
            return provider.createImportGlobal(value, mutability);
        }
        return GlobalInstance.builder().value(value).mutabilityType(mutability).build();
    }

    private synchronized <T> T own(Supplier<T> create) {
        if (closed) {
            throw new IllegalStateException("This import factory has been closed");
        }
        T resource = create.get();
        if (resource instanceof NativeResource) {
            created.add((NativeResource) resource);
        }
        return resource;
    }

    /** Frees what this factory made, or nothing while an open instance still imports it. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        Borrowers.tryCloseAll(created);
        closed = true;
        RuntimeException failure = null;
        for (var resource : created) {
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
        created.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
