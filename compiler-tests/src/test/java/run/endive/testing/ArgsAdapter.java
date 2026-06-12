package run.endive.testing;

import java.util.ArrayList;
import java.util.List;
import run.endive.runtime.CallResult;
import run.endive.runtime.ExportFunction;

public final class ArgsAdapter {
    private final List<Long> longs = new ArrayList<>();
    private final List<Object> refs = new ArrayList<>();
    private boolean hasRefs;

    private ArgsAdapter() {}

    public static ArgsAdapter builder() {
        return new ArgsAdapter();
    }

    public ArgsAdapter add(long arg) {
        longs.add(arg);
        refs.add(null);
        return this;
    }

    public ArgsAdapter add(long[] args) {
        for (var arg : args) {
            longs.add(arg);
            refs.add(null);
        }
        return this;
    }

    public ArgsAdapter addRef(Object ref) {
        longs.add(0L);
        refs.add(ref);
        hasRefs = true;
        return this;
    }

    public long[] build() {
        var result = new long[longs.size()];
        for (int i = 0; i < longs.size(); i++) {
            result[i] = longs.get(i);
        }
        return result;
    }

    public Object[] buildRefs() {
        if (!hasRefs) {
            return null;
        }
        return refs.toArray();
    }

    public CallResult applyWithRefs(ExportFunction func) {
        return func.applyWithRefs(build(), buildRefs());
    }
}
