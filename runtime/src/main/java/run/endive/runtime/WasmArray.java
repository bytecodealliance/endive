package run.endive.runtime;

/**
 * Runtime representation of a WasmGC array instance.
 * Numeric elements are stored in {@code elements} (long[]).
 * Reference-typed elements are stored in {@code elementRefs} (Object[]).
 * Packed types (i8, i16) are stored as full long slots for simplicity.
 */
public final class WasmArray implements WasmGcRef {
    private final int typeIdx;
    private final long[] elements;
    private Object[] elementRefs;

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated(since = "use builder()")
    public WasmArray(int typeIdx, long[] elements) {
        this(typeIdx, elements, null);
    }

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated(since = "use builder()")
    public WasmArray(int typeIdx, long[] elements, Object[] elementRefs) {
        this.typeIdx = typeIdx;
        this.elements = elements;
        this.elementRefs = elementRefs;
    }

    @Override
    public int typeIdx() {
        return typeIdx;
    }

    public long get(int idx) {
        return elements[idx];
    }

    public void set(int idx, long value) {
        elements[idx] = value;
    }

    public Object getRef(int idx) {
        return elementRefs != null ? elementRefs[idx] : null;
    }

    public void setRef(int idx, Object ref) {
        if (elementRefs == null) {
            elementRefs = new Object[elements.length];
        }
        elementRefs[idx] = ref;
    }

    public int length() {
        return elements.length;
    }

    public long[] elements() {
        return elements;
    }

    public Object[] elementRefs() {
        return elementRefs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int typeIdx;
        private long[] elements;
        private Object[] elementRefs;

        private Builder() {}

        public Builder typeIdx(int typeIdx) {
            this.typeIdx = typeIdx;
            return this;
        }

        public Builder elements(long[] elements) {
            this.elements = elements;
            return this;
        }

        public Builder elementRefs(Object[] elementRefs) {
            this.elementRefs = elementRefs;
            return this;
        }

        @SuppressWarnings("deprecation")
        public WasmArray build() {
            return new WasmArray(typeIdx, elements, elementRefs);
        }
    }
}
