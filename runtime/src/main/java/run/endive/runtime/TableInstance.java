package run.endive.runtime;

import static run.endive.wasm.types.Value.REF_NULL_VALUE;

import java.util.Arrays;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;

public class TableInstance {

    private final Table table;
    private Instance[] instances;
    private int[] refs;

    public TableInstance(Table table, int initialValue) {
        this.table = table;
        this.instances = new Instance[(int) table.limits().min()];
        refs = new int[(int) table.limits().min()];
        Arrays.fill(refs, initialValue);
    }

    public int size() {
        return refs.length;
    }

    public ValType elementType() {
        return table.elementType();
    }

    public TableLimits limits() {
        return table.limits();
    }

    public int grow(int size, int value, Instance instance) {
        var oldSize = refs.length;
        var targetSize = oldSize + size;
        if (size < 0 || targetSize > limits().max()) {
            return -1;
        }
        var newRefs = Arrays.copyOf(refs, targetSize);
        Arrays.fill(newRefs, oldSize, targetSize, value);
        var newInstances = Arrays.copyOf(instances, targetSize);
        Arrays.fill(newInstances, oldSize, targetSize, instance);
        refs = newRefs;
        instances = newInstances;
        table.limits().grow(size);
        return oldSize;
    }

    public int ref(int index) {
        if (index < 0 || index >= this.refs.length) {
            throw new WasmEngineException("undefined element");
        }
        return this.refs[index];
    }

    public int requiredRef(int index) {
        int ref = ref(index);
        if (ref == REF_NULL_VALUE) {
            throw new WasmEngineException("uninitialized element " + index);
        }
        return ref;
    }

    public void setRef(int index, int value, Instance instance) {
        if (index < 0 || index >= this.refs.length || index >= this.instances.length) {
            throw new UninstantiableException("out of bounds table access");
        }
        this.refs[index] = value;
        this.instances[index] = instance;
    }

    public Instance instance(int index) {
        return instances[index];
    }

    public void reset() {
        for (int i = 0; i < refs.length; i++) {
            this.refs[i] = REF_NULL_VALUE;
        }
    }
}
