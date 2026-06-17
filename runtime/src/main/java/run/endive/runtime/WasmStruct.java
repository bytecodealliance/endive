package run.endive.runtime;

/**
 * Runtime representation of a WasmGC struct instance.
 * Numeric fields are stored in {@code fields} (long[]).
 * Reference-typed fields are stored in {@code fieldRefs} (Object[]).
 * Both arrays are indexed by field index; only one is "active" per field.
 */
public final class WasmStruct implements WasmGcRef {
    private final int typeIdx;
    private final long[] fields;
    private Object[] fieldRefs;

    public WasmStruct(int typeIdx, long[] fields) {
        this(typeIdx, fields, null);
    }

    public WasmStruct(int typeIdx, long[] fields, Object[] fieldRefs) {
        this.typeIdx = typeIdx;
        this.fields = fields;
        this.fieldRefs = fieldRefs;
    }

    @Override
    public int typeIdx() {
        return typeIdx;
    }

    public long field(int idx) {
        return fields[idx];
    }

    public void setField(int idx, long value) {
        fields[idx] = value;
    }

    public Object fieldRef(int idx) {
        return fieldRefs != null ? fieldRefs[idx] : null;
    }

    public void setFieldRef(int idx, Object ref) {
        if (fieldRefs == null) {
            fieldRefs = new Object[fields.length];
        }
        fieldRefs[idx] = ref;
    }

    public int fieldCount() {
        return fields.length;
    }
}
