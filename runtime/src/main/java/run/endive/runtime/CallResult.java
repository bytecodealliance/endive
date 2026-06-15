package run.endive.runtime;

/** Holds the dual long[] + Object[] result of a Wasm function call that may return GC/externref values. */
public final class CallResult {
    private final long[] longs;
    private final Object[] refs;

    public CallResult(long[] longs, Object[] refs) {
        this.longs = longs;
        this.refs = refs;
    }

    public long[] longs() {
        return longs;
    }

    public Object[] refs() {
        return refs;
    }

    public long longResult(int i) {
        return longs != null ? longs[i] : 0;
    }

    public Object refResult(int i) {
        return refs != null ? refs[i] : null;
    }
}
