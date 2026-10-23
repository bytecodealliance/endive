package run.endive.redline.experimental.api.internal;

/** A native memory or table, freed by the instance that defines it or the factory that made it. */
public interface NativeResource extends AutoCloseable {

    Borrowers borrowers();

    /** Frees the resource. Idempotent; refuses while an open instance still imports it. */
    @Override
    void close();
}
