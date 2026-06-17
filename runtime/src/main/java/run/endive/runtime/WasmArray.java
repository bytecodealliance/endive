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

    public WasmArray(int typeIdx, long[] elements) {
        this(typeIdx, elements, null);
    }

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
}
