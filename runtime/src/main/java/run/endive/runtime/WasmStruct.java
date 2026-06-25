package run.endive.runtime;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated(since = "use builder()")
    public WasmStruct(int typeIdx, long[] fields) {
        this(typeIdx, fields, null);
    }

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated(since = "use builder()")
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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int typeIdx;
        private long[] fields;
        private Object[] fieldRefs;
        private List<Long> fieldList;
        private List<Object> fieldRefList;

        private Builder() {}

        public Builder typeIdx(int typeIdx) {
            this.typeIdx = typeIdx;
            return this;
        }

        public Builder fields(long[] fields) {
            this.fields = fields;
            return this;
        }

        public Builder fieldRefs(Object[] fieldRefs) {
            this.fieldRefs = fieldRefs;
            return this;
        }

        public Builder addField(long value) {
            ensureLists();
            fieldList.add(value);
            fieldRefList.add(null);
            return this;
        }

        public Builder addFieldRef(Object ref) {
            ensureLists();
            fieldList.add(0L);
            fieldRefList.add(ref);
            return this;
        }

        private void ensureLists() {
            if (fieldList == null) {
                fieldList = new ArrayList<>();
                fieldRefList = new ArrayList<>();
            }
        }

        @SuppressWarnings("deprecation")
        public WasmStruct build() {
            if (fieldList != null) {
                int size = fieldList.size();
                long[] f = new long[size];
                boolean hasRefs = false;
                for (int i = 0; i < size; i++) {
                    f[i] = fieldList.get(i);
                    if (fieldRefList.get(i) != null) {
                        hasRefs = true;
                    }
                }
                Object[] r = null;
                if (hasRefs) {
                    r = fieldRefList.toArray();
                }
                return new WasmStruct(typeIdx, f, r);
            }
            return new WasmStruct(typeIdx, fields, fieldRefs);
        }
    }
}
