package run.endive.runtime;

/**
 * Wrapper for externref values.
 * Can wrap either a long (for host-provided externref values) or an Object
 * (for GC values externalized via extern.convert_any).
 */
public final class WasmExternRef implements WasmGcRef {

    private static final int ANY_HEAP_TYPE = -18; // ValType.TypeIdxCode.ANY.code()

    private final long longValue;
    private final Object objectValue;

    public WasmExternRef(long value) {
        this.longValue = value;
        this.objectValue = null;
    }

    public WasmExternRef(Object value) {
        this.longValue = 0;
        this.objectValue = value;
    }

    @Override
    public int typeIdx() {
        return ANY_HEAP_TYPE;
    }

    public long value() {
        return longValue;
    }

    public Object objectValue() {
        return objectValue;
    }

    public boolean isObjectRef() {
        return objectValue != null;
    }
}
