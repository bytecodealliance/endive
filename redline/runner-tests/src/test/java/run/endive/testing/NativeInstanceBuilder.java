package run.endive.testing;

import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
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
                        NativeCompiler.compileAll(
                                RedlineTarget.detectHost().orElseThrow().triple(), m));
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
