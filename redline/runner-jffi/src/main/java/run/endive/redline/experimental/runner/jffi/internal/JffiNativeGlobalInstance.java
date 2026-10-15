package run.endive.redline.experimental.runner.jffi.internal;

import com.kenai.jffi.MemoryIO;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Instance;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/** A global in its instance's off-heap globals buffer while bound, and on the heap otherwise. */
public final class JffiNativeGlobalInstance extends GlobalInstance {

    private static final MemoryIO MEM = MemoryIO.getInstance();

    /** Created by the host to be imported, so an instance may adopt it while detached. */
    private final boolean hostCreated;

    // 0 while detached
    private long bufferAddress;
    private long offset;

    private JffiNativeGlobalInstance(
            long initialValue,
            ValType valType,
            MutabilityType mutabilityType,
            boolean hostCreated) {
        super(valType, mutabilityType, initialValue, 0);
        this.hostCreated = hostCreated;
    }

    /** A global a module defines, bound to its instance's buffer by {@link #setInstance}. */
    public static JffiNativeGlobalInstance defined(
            long initialValue, ValType valType, MutabilityType mutabilityType) {
        return new JffiNativeGlobalInstance(initialValue, valType, mutabilityType, false);
    }

    /** A global for the host to pass in as an import. */
    public static JffiNativeGlobalInstance hostCreated(
            long initialValue, ValType valType, MutabilityType mutabilityType) {
        return new JffiNativeGlobalInstance(initialValue, valType, mutabilityType, true);
    }

    /** Only a host global no instance is using yet can be adopted. */
    boolean isAdoptable() {
        return hostCreated && bufferAddress == 0;
    }

    @Override
    public void setInstance(Instance instance) {
        super.setInstance(instance);
        if (instance.getMachine() instanceof JffiNativeMachine) {
            ((JffiNativeMachine) instance.getMachine()).bindDefinedGlobal(this);
        }
    }

    /** Moves the value onto slot {@code index} of {@code buffer}, carrying it across. */
    void bind(long buffer, int index) {
        long current = getValue();
        this.bufferAddress = buffer;
        this.offset = (long) index * 8;
        MEM.putLong(buffer + offset, current);
    }

    /** Moves the value back to the heap, before the buffer it sits on is freed. */
    void detach() {
        if (bufferAddress != 0) {
            long current = getValue();
            bufferAddress = 0;
            super.setValueLow(current);
        }
    }

    @Override
    public long getValue() {
        return bufferAddress == 0 ? super.getValueLow() : MEM.getLong(bufferAddress + offset);
    }

    @Override
    public long getValueLow() {
        return getValue();
    }

    @Override
    public void setValue(long value) {
        setValueLow(value);
    }

    @Override
    public void setValue(Value value) {
        checkType(value);
        setValueLow(value.raw());
    }

    @Override
    public void setValueLow(long value) {
        if (bufferAddress == 0) {
            super.setValueLow(value);
        } else {
            MEM.putLong(bufferAddress + offset, value);
        }
    }
}
