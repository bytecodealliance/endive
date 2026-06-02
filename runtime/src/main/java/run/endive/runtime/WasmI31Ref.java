package run.endive.runtime;

/**
 * Representation of an i31ref value.
 * On the stack, stored as Object in the refs array.
 * Two i31ref values with the same integer value are equal per the Wasm spec (ref.eq).
 */
public final class WasmI31Ref implements WasmGcRef {

    private static final int I31_HEAP_TYPE = -20; // ValType.TypeIdxCode.I31.code()

    private final int value;

    public WasmI31Ref(int value) {
        this.value = value & 0x7FFFFFFF; // 31-bit value
    }

    @Override
    public int typeIdx() {
        return I31_HEAP_TYPE;
    }

    public int value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WasmI31Ref)) {
            return false;
        }
        return value == ((WasmI31Ref) o).value;
    }

    @Override
    public int hashCode() {
        return value;
    }
}
