package run.endive.testing;

import run.endive.redline.api.RedlineTarget;
import run.endive.redline.compiler.internal.NativeCompiler;
import run.endive.redline.runner.NativeMachineFactory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasm.WasmModule;

public final class NativeInstanceBuilder {

    private final NativeMachineFactory.Builder delegate;

    private NativeInstanceBuilder(NativeMachineFactory.Builder delegate) {
        this.delegate = delegate;
    }

    public static NativeInstanceBuilder builder(WasmModule module) {
        var b = NativeMachineFactory.builder(module);
        b.withCompilerFunction(
                m ->
                        new NativeCompiler(RedlineTarget.detectHost().orElseThrow().triple(), m)
                                .compileAll());
        return new NativeInstanceBuilder(b);
    }

    public NativeInstanceBuilder withImportValues(ImportValues importValues) {
        delegate.withImportValues(importValues);
        return this;
    }

    public NativeInstanceBuilder withStart(boolean start) {
        delegate.withStart(start);
        return this;
    }

    public Instance build() {
        return delegate.build();
    }
}
