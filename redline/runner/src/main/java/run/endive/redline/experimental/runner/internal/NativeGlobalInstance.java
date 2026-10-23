package run.endive.redline.experimental.runner.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Instance;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/** A global in its instance's off-heap globals buffer while bound, and on the heap otherwise. */
public final class NativeGlobalInstance extends GlobalInstance {

    /** Created by the host to be imported, so an instance may adopt it while detached. */
    private final boolean hostCreated;

    private MemorySegment buffer;
    private long offset;
    private boolean bound;

    private NativeGlobalInstance(
            long initialValue,
            ValType valType,
            MutabilityType mutabilityType,
            boolean hostCreated) {
        super(valType, mutabilityType, initialValue, 0);
        this.hostCreated = hostCreated;
        this.buffer = heapCell(initialValue);
    }

    private static MemorySegment heapCell(long value) {
        return MemorySegment.ofArray(new long[] {value});
    }

    /** A global a module defines, bound to its instance's buffer by {@link #setInstance}. */
    public static NativeGlobalInstance defined(
            long initialValue, ValType valType, MutabilityType mutabilityType) {
        return new NativeGlobalInstance(initialValue, valType, mutabilityType, false);
    }

    /** A global for the host to pass in as an import. */
    public static NativeGlobalInstance hostCreated(
            long initialValue, ValType valType, MutabilityType mutabilityType) {
        return new NativeGlobalInstance(initialValue, valType, mutabilityType, true);
    }

    /** Only a host global no instance is using yet can be adopted. */
    boolean isAdoptable() {
        return hostCreated && !bound;
    }

    @Override
    public void setInstance(Instance instance) {
        super.setInstance(instance);
        if (instance.getMachine() instanceof NativeMachine machine) {
            machine.bindDefinedGlobal(this);
        }
    }

    /** Moves the value onto slot {@code index} of {@code target}, carrying it across. */
    void bind(MemorySegment target, int index) {
        long current = getValue();
        this.buffer = target;
        this.offset = (long) index * 8;
        this.bound = true;
        target.set(ValueLayout.JAVA_LONG, offset, current);
    }

    /** Moves the value back to the heap, before the buffer it sat on is freed. */
    void detach(long value) {
        this.buffer = heapCell(value);
        this.offset = 0;
        this.bound = false;
    }

    @Override
    public long getValue() {
        return buffer.get(ValueLayout.JAVA_LONG, offset);
    }

    @Override
    public long getValueLow() {
        return buffer.get(ValueLayout.JAVA_LONG, offset);
    }

    @Override
    public void setValue(long value) {
        buffer.set(ValueLayout.JAVA_LONG, offset, value);
    }

    @Override
    public void setValue(Value value) {
        checkType(value);
        buffer.set(ValueLayout.JAVA_LONG, offset, value.raw());
    }

    @Override
    public void setValueLow(long value) {
        buffer.set(ValueLayout.JAVA_LONG, offset, value);
    }
}
