package run.endive.runtime;

import run.endive.wasm.types.Table;

/**
 * Factory for creating {@link TableInstance} objects during module instantiation.
 */
@FunctionalInterface
public interface TableFactory {
    TableInstance create(Table table, int initValue);
}
