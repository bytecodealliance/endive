package run.endive.runtime;

import static run.endive.wasm.types.Value.REF_NULL_VALUE;

public class MStack {
    public static final int MIN_CAPACITY = 8;

    private int count;
    private long[] elements;
    private Object[] refs;

    public MStack() {
        this.elements = new long[MIN_CAPACITY];
    }

    private void increaseCapacity() {
        final int newCapacity = elements.length << 1;

        final long[] array = new long[newCapacity];
        System.arraycopy(elements, 0, array, 0, elements.length);
        elements = array;

        if (refs != null) {
            final Object[] refArray = new Object[newCapacity];
            System.arraycopy(refs, 0, refArray, 0, refs.length);
            refs = refArray;
        }
    }

    // internal use only!
    public long[] array() {
        return elements;
    }

    public Object[] refArray() {
        return refs;
    }

    public void push(long v) {
        elements[count] = v;
        count++;

        if (count == elements.length) {
            increaseCapacity();
        }
    }

    public void pushRef(Object ref) {
        if (refs == null) {
            refs = new Object[elements.length];
        }
        elements[count] = (ref == null) ? REF_NULL_VALUE : 0;
        refs[count] = ref;
        count++;

        if (count == elements.length) {
            increaseCapacity();
        }
    }

    public long pop() {
        count--;
        return elements[count];
    }

    public Object popRef() {
        count--;
        Object ref = refs[count];
        refs[count] = null;
        return ref;
    }

    public long peek() {
        return elements[count - 1];
    }

    public Object peekRef() {
        return refs[count - 1];
    }

    public int size() {
        return count;
    }

    public void clearRefsTo(int newSize) {
        if (refs != null) {
            for (int i = count - 1; i >= newSize; i--) {
                refs[i] = null;
            }
        }
    }
}
