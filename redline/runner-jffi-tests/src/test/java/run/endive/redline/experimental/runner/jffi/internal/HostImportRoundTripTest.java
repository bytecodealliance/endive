package run.endive.redline.experimental.runner.jffi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/**
 * Values crossing the host boundary are marshalled by hand in each runner, so each
 * conversion is a place they can be mangled. The spec suite does not reach these:
 * it drives modules that are self-contained rather than calling back into Java.
 */
public class HostImportRoundTripTest {

    @Test
    public void floatResultKeepsItsBitPattern() {
        try (var instance = buildInstance()) {
            assertEquals(
                    1.5f,
                    Value.longToFloat(instance.export("callRetF32").apply()[0]),
                    "a float result must be reinterpreted, not converted numerically");
        }
    }

    @Test
    public void doubleResultKeepsItsBitPattern() {
        try (var instance = buildInstance()) {
            assertEquals(
                    2.5d,
                    Value.longToDouble(instance.export("callRetF64").apply()[0]),
                    "a double result must be reinterpreted, not converted numerically");
        }
    }

    @Test
    public void negativeI32ArgumentArrivesSignExtended() {
        try (var instance = buildInstance()) {
            assertEquals(
                    1,
                    (int) instance.export("callTakeI32").apply()[0],
                    "the host must be handed -1, not 4294967295");
        }
    }

    @Test
    public void multiValueResultKeepsEveryValue() {
        try (var instance = buildInstance()) {
            assertEquals(
                    30,
                    (int) instance.export("callRetPairSum").apply()[0],
                    "both results of a multi-value host import must arrive");
        }
    }

    private static Instance buildInstance() {
        var module =
                Parser.parse(
                        CorpusResources.getResource("compiled/host-import-roundtrip.wat.wasm"));

        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "retF32",
                                        FunctionType.of(
                                                java.util.List.of(),
                                                java.util.List.of(ValType.F32)),
                                        (inst, args) -> new long[] {Value.floatToLong(1.5f)}),
                                new HostFunction(
                                        "host",
                                        "retF64",
                                        FunctionType.of(
                                                java.util.List.of(),
                                                java.util.List.of(ValType.F64)),
                                        (inst, args) -> new long[] {Value.doubleToLong(2.5d)}),
                                new HostFunction(
                                        "host",
                                        "takeI32",
                                        FunctionType.of(
                                                java.util.List.of(ValType.I32),
                                                java.util.List.of(ValType.I32)),
                                        // Reports on the raw long it was handed rather
                                        // than echoing it: an echo would be truncated
                                        // back to -1 on the way out and hide a
                                        // zero-extended argument.
                                        (inst, args) -> new long[] {args[0] == -1L ? 1 : 0}),
                                new HostFunction(
                                        "host",
                                        "retPair",
                                        FunctionType.of(
                                                java.util.List.of(),
                                                java.util.List.of(ValType.I32, ValType.I32)),
                                        (inst, args) -> new long[] {10, 20}))
                        .build();

        return JffiNativeMachineFactory.builder(module)
                .withImportValues(imports)
                .withCompilerFunction(
                        m ->
                                NativeCompiler.compileAll(
                                        RedlineTarget.detectHost().orElseThrow().triple(), m))
                .build();
    }
}
