package run.endive.runtime;

import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;
import run.endive.wasm.types.ValueType;

public class GlobalInstance {
    private long valueLow;
    private long valueHigh;
    private Object refValue;
    private final ValType valType;
    private Instance instance;
    private final MutabilityType mutabilityType;

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated
    public GlobalInstance(Value value) {
        this(value, MutabilityType.Const);
    }

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated
    public GlobalInstance(Value value, MutabilityType mutabilityType) {
        this.valueLow = value.raw();
        this.valueHigh = 0;
        this.valType = value.type();
        this.mutabilityType = mutabilityType;
    }

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated
    public GlobalInstance(
            long valueLow, long valueHigh, ValueType valueType, MutabilityType mutabilityType) {
        this.valueLow = valueLow;
        this.valueHigh = valueHigh;
        this.valType = valueType.toValType();
        this.mutabilityType = mutabilityType;
    }

    /**
     * @deprecated use {@link #builder()}
     */
    @Deprecated
    public GlobalInstance(
            long valueLow, long valueHigh, ValType valType, MutabilityType mutabilityType) {
        this.valueLow = valueLow;
        this.valueHigh = valueHigh;
        this.valType = valType;
        this.mutabilityType = mutabilityType;
    }

    protected GlobalInstance(
            ValType valType, MutabilityType mutabilityType, long valueLow, long valueHigh) {
        this.valueLow = valueLow;
        this.valueHigh = valueHigh;
        this.valType = valType;
        this.mutabilityType = mutabilityType;
    }

    private GlobalInstance(Builder b) {
        this.valueLow = b.valueLow;
        this.valueHigh = b.valueHigh;
        this.refValue = b.refValue;
        this.valType = b.valType;
        this.mutabilityType = b.mutabilityType;
    }

    public long getValueLow() {
        return valueLow;
    }

    public long getValueHigh() {
        return valueHigh;
    }

    public long getValue() {
        return valueLow;
    }

    public ValType getType() {
        return valType;
    }

    public void setValue(Value value) {
        assert (value.type() == valType);
        this.valueLow = value.raw();
    }

    public void setValue(long value) {
        this.valueLow = value;
    }

    public void setValueLow(long value) {
        this.valueLow = value;
    }

    public void setValueHigh(long value) {
        this.valueHigh = value;
    }

    public Instance getInstance() {
        return instance;
    }

    public void setInstance(Instance instance) {
        this.instance = instance;
    }

    public MutabilityType getMutabilityType() {
        return mutabilityType;
    }

    public Object getRefValue() {
        return refValue;
    }

    public void setRefValue(Object ref) {
        this.refValue = ref;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long valueLow;
        private long valueHigh;
        private Object refValue;
        private ValType valType;
        private MutabilityType mutabilityType = MutabilityType.Const;

        private Builder() {}

        public Builder value(Value value) {
            this.valueLow = value.raw();
            this.valType = value.type();
            return this;
        }

        public Builder valueLow(long valueLow) {
            this.valueLow = valueLow;
            return this;
        }

        public Builder valueHigh(long valueHigh) {
            this.valueHigh = valueHigh;
            return this;
        }

        public Builder refValue(Object refValue) {
            this.refValue = refValue;
            return this;
        }

        public Builder valType(ValType valType) {
            this.valType = valType;
            return this;
        }

        public Builder mutabilityType(MutabilityType mutabilityType) {
            this.mutabilityType = mutabilityType;
            return this;
        }

        public GlobalInstance build() {
            return new GlobalInstance(this);
        }
    }
}
