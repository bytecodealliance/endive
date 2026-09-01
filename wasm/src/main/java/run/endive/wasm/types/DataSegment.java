package run.endive.wasm.types;

import java.util.Arrays;
import java.util.Objects;

public abstract class DataSegment {
    private final byte[] data;

    DataSegment(byte[] data) {
        this.data = data.clone();
    }

    public byte[] data() {
        return data.clone();
    }

    /**
     * The segment's bytes, without copying. The returned array is the segment's own storage,
     * shared with every other reader, and must never be modified.
     *
     * <p>Use {@link #data()} unless the copy it makes is unaffordable. This method exists for
     * read-only consumers that access a segment repeatedly, where copying it every time would
     * cost more than the read itself.
     */
    public byte[] bytes() {
        return data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DataSegment)) {
            return false;
        }
        DataSegment that = (DataSegment) o;
        return Objects.deepEquals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
