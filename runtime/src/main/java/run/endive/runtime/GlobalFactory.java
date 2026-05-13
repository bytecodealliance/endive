package run.endive.runtime;

import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.ValType;

/**
 * Factory for creating {@link GlobalInstance} objects during module instantiation.
 */
@FunctionalInterface
public interface GlobalFactory {
    GlobalInstance create(long value, long highValue, ValType type, MutabilityType mutability);
}
