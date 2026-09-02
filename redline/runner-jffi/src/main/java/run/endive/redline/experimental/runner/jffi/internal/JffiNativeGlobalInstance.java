package run.endive.redline.experimental.runner.jffi.internal;

import com.kenai.jffi.MemoryIO;
import run.endive.runtime.GlobalInstance;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/**
 * GlobalInstance backed by an off-heap buffer via jffi MemoryIO.
 * Native code and Java code read/write the same memory — no sync needed.
 */
public final class JffiNativeGlobalInstance extends GlobalInstance {

    private static final MemoryIO MEM = MemoryIO.getInstance();

    private final long bufferAddress;
    private final long offset;

    public JffiNativeGlobalInstance(
            long bufferAddress,
            int index,
            long initialValue,
            ValType valType,
            MutabilityType mutabilityType) {
        super(valType, mutabilityType, initialValue, 0);
        this.bufferAddress = bufferAddress;
        this.offset = (long) index * 8;
        // Write initial value to buffer
        MEM.putLong(bufferAddress + offset, initialValue);
    }

    @Override
    public long getValue() {
        return MEM.getLong(bufferAddress + offset);
    }

    @Override
    public long getValueLow() {
        return MEM.getLong(bufferAddress + offset);
    }

    @Override
    public void setValue(long value) {
        MEM.putLong(bufferAddress + offset, value);
    }

    @Override
    public void setValue(Value value) {
        MEM.putLong(bufferAddress + offset, value.raw());
    }

    @Override
    public void setValueLow(long value) {
        MEM.putLong(bufferAddress + offset, value);
    }
}
