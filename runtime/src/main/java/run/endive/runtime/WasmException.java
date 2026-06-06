package run.endive.runtime;

public class WasmException extends RuntimeException {
    private final int tagIdx;
    private final long[] args;
    private final Object[] refArgs;
    private final Instance instance;

    public WasmException(Instance instance, int tagIdx, long[] args) {
        this(instance, tagIdx, args, null);
    }

    public WasmException(Instance instance, int tagIdx, long[] args, Object[] refArgs) {
        this.instance = instance;
        this.tagIdx = tagIdx;
        this.args = args.clone();
        this.refArgs = (refArgs != null) ? refArgs.clone() : null;
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
}
