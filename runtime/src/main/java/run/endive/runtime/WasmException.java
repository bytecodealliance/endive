package run.endive.runtime;

public class WasmException extends RuntimeException {
    private final int tagIdx;
    private final long[] args;
    private final Object[] refArgs;
    private final Instance instance;

    private WasmException(Instance instance, int tagIdx, long[] args, Object[] refArgs) {
        super(
                null,
                null,
                false, // disable attaching suppressed exceptions
                false // disable stack trace
                );
        this.instance = instance;
        this.tagIdx = tagIdx;
        this.args = args;
        this.refArgs = refArgs;
    }

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated(since = "use builder()")
    public WasmException(Instance instance, int tagIdx, long[] args) {
        this(instance, tagIdx, args.clone(), null);
    }

    private WasmException(Builder b) {
        this(
                b.instance,
                b.tagIdx,
                (b.args != null) ? b.args.clone() : null,
                (b.refArgs != null) ? b.refArgs.clone() : null);
    }

    public Instance instance() {
        return instance;
    }

    public int tagIdx() {
        return tagIdx;
    }

    public long[] args() {
        return args;
    }

    public Object[] refArgs() {
        return refArgs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Instance instance;
        private int tagIdx;
        private long[] args;
        private Object[] refArgs;

        private Builder() {}

        public Builder instance(Instance instance) {
            this.instance = instance;
            return this;
        }

        public Builder tagIdx(int tagIdx) {
            this.tagIdx = tagIdx;
            return this;
        }

        public Builder args(long[] args) {
            this.args = args;
            return this;
        }

        public Builder refArgs(Object[] refArgs) {
            this.refArgs = refArgs;
            return this;
        }

        public WasmException build() {
            return new WasmException(this);
        }
    }
}
