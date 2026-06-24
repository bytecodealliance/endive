package run.endive.runtime;

/** Holds the dual long[] + Object[] result of a Wasm function call that may return GC/externref values. */
public final class CallResult {
    private final long[] longs;
    private final Object[] refs;

    private CallResult(long[] longs, Object[] refs) {
        this.longs = longs;
        this.refs = refs;
    }

    public static CallResult of(long[] longs, Object[] refs) {
        return new CallResult(longs, refs);
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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long[] longs;
        private Object[] refs;

        private Builder() {}

        public Builder longs(long[] longs) {
            this.longs = longs;
            return this;
        }

        public Builder refs(Object[] refs) {
            this.refs = refs;
            return this;
        }

        public CallResult build() {
            return new CallResult(longs, refs);
        }
    }
}
