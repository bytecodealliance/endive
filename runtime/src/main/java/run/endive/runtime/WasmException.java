package run.endive.runtime;

public class WasmException extends RuntimeException {
    private final int tagIdx;
    private final long[] args;
    private final Object[] refArgs;
    private final Instance instance;

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated(since = "use builder()")
    public WasmException(Instance instance, int tagIdx, long[] args) {
        this.instance = instance;
        this.tagIdx = tagIdx;
        this.args = args.clone();
        this.refArgs = null;
        this.setStackTrace(new StackTraceElement[0]);
    }

    private WasmException(Builder b) {
        this.instance = b.instance;
        this.tagIdx = b.tagIdx;
        this.args = (b.args != null) ? b.args.clone() : null;
        this.refArgs = (b.refArgs != null) ? b.refArgs.clone() : null;
        this.setStackTrace(new StackTraceElement[0]);
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
