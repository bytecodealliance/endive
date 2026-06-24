package run.endive.runtime;

/**
 * Represents a Java function that can be called from Wasm.
 */
@FunctionalInterface
@SuppressWarnings("deprecation")
public interface WasmFunctionHandle {
    long[] apply(Instance instance, long... args);

    /**
     * Call this host function with separate long and Object ref arguments.
     * Override this method for host functions that need to receive/return
     * externref or GC reference values as Objects.
     *
     * <p>The default delegates to {@link #apply(Instance, long...)} which discards
     * Object refs; override to handle them.
     */
    default CallResult applyWithRefs(Instance instance, long[] args, Object[] refArgs) {
        return CallResult.of(apply(instance, args), null);
    }
}
