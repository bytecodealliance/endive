package run.endive.tools.wasm;

/**
 * WebAssembly features and feature groups for use with {@link Validate}.
 *
 * <p>Feature groups ({@link #MVP}, {@link #WASM1}, {@link #WASM2}, {@link #WASM3}, {@link #LIME1})
 * reset the feature set to a predefined baseline. Individual features can then be enabled or
 * disabled on top. Order matters: groups reset, then individual features toggle.
 *
 * <p>The flag strings correspond to the {@code wasm-tools validate --features} CLI flags from
 * wasm-tools v1.240.0.
 */
public enum WasmFeature {

    // Feature groups
    MVP("mvp"),
    WASM1("wasm1"),
    WASM2("wasm2"),
    WASM3("wasm3"),
    LIME1("lime1"),

    // Meta-flag
    ALL("all"),

    // Individual features
    MUTABLE_GLOBAL("mutable-global"),
    SATURATING_FLOAT_TO_INT("saturating-float-to-int"),
    SIGN_EXTENSION("sign-extension"),
    REFERENCE_TYPES("reference-types"),
    MULTI_VALUE("multi-value"),
    BULK_MEMORY("bulk-memory"),
    SIMD("simd"),
    RELAXED_SIMD("relaxed-simd"),
    THREADS("threads"),
    SHARED_EVERYTHING_THREADS("shared-everything-threads"),
    TAIL_CALL("tail-call"),
    FLOATS("floats"),
    MULTI_MEMORY("multi-memory"),
    EXCEPTIONS("exceptions"),
    MEMORY64("memory64"),
    EXTENDED_CONST("extended-const"),
    COMPONENT_MODEL("component-model"),
    FUNCTION_REFERENCES("function-references"),
    MEMORY_CONTROL("memory-control"),
    GC("gc"),
    CUSTOM_PAGE_SIZES("custom-page-sizes"),
    LEGACY_EXCEPTIONS("legacy-exceptions"),
    GC_TYPES("gc-types"),
    STACK_SWITCHING("stack-switching"),
    WIDE_ARITHMETIC("wide-arithmetic"),
    CM_VALUES("cm-values"),
    CM_NESTED_NAMES("cm-nested-names"),
    CM_ASYNC("cm-async"),
    CM_ASYNC_STACKFUL("cm-async-stackful"),
    CM_ASYNC_BUILTINS("cm-async-builtins"),
    CM_THREADING("cm-threading"),
    CM_ERROR_CONTEXT("cm-error-context"),
    CM_FIXED_SIZE_LIST("cm-fixed-size-list"),
    CM_GC("cm-gc"),
    CALL_INDIRECT_OVERLONG("call-indirect-overlong"),
    BULK_MEMORY_OPT("bulk-memory-opt");

    private final String flag;

    WasmFeature(String flag) {
        this.flag = flag;
    }

    public String flag() {
        return flag;
    }

    public String negatedFlag() {
        return "-" + flag;
    }
}
