package run.endive.runtime;

/**
 * Represents a Java function that can be called from Wasm.
 */
@FunctionalInterface
public interface WasmFunctionHandle {
    long[] apply(Instance instance, long... args);

    default Object[] applyGc(Instance instance, Object... args) {
        throw new UnsupportedOperationException(
                "This host function does not support GC references");
    }
}
