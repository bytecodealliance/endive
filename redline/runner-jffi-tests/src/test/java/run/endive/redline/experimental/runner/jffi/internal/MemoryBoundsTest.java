package run.endive.redline.experimental.runner.jffi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.UninstantiableException;
import run.endive.wasm.types.MemoryLimits;

/**
 * A host reading past the end of a Wasm memory has to trap the same way it would
 * on any other backend. The spec suite drives memory from inside the module, so it
 * never exercises these accessors.
 */
public class MemoryBoundsTest {

    private static final int PAGE = 65536;

    @Test
    public void readPastTheEndTraps() {
        var memory = JffiNativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        assertThrows(WasmRuntimeException.class, () -> memory.readInt(PAGE));
        assertThrows(WasmRuntimeException.class, () -> memory.readLong(PAGE - 4));
        assertThrows(WasmRuntimeException.class, () -> memory.read(PAGE));
        assertThrows(WasmRuntimeException.class, () -> memory.readShort(PAGE - 1));
        assertThrows(WasmRuntimeException.class, () -> memory.readBytes(PAGE - 1, 8));
    }

    @Test
    public void writePastTheEndTraps() {
        var memory = JffiNativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        assertThrows(WasmRuntimeException.class, () -> memory.writeI32(PAGE, 1));
        assertThrows(WasmRuntimeException.class, () -> memory.writeLong(PAGE - 4, 1L));
        assertThrows(WasmRuntimeException.class, () -> memory.writeByte(PAGE, (byte) 1));
        assertThrows(WasmRuntimeException.class, () -> memory.writeShort(PAGE - 1, (short) 1));
    }

    @Test
    public void insideTheMemoryIsUntouched() {
        var memory = JffiNativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        memory.writeI32(PAGE - 4, 0x11223344);
        org.junit.jupiter.api.Assertions.assertEquals(0x11223344, memory.readInt(PAGE - 4));
    }

    @Test
    public void wideAccessesAreLittleEndianAtOddOffsets() {
        var memory = JffiNativeMachineFactory.createMemory(new MemoryLimits(1, 1));
        for (int i = 0; i < 8; i++) {
            memory.writeByte(101 + i, (byte) (i + 1));
        }
        assertEquals((short) 0x0201, memory.readShort(101));
        assertEquals(0x0807060504030201L, memory.readLong(101));

        memory.writeShort(201, (short) 0x0201);
        memory.writeLong(301, 0x0807060504030201L);
        assertEquals(1, memory.read(201));
        assertEquals(2, memory.read(202));
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, memory.read(301 + i));
        }
    }

    @Test
    public void anInitialSizePastTheRuntimeLimitIsRejected() {
        assertThrows(
                UninstantiableException.class,
                () ->
                        JffiNativeMachineFactory.createMemory(
                                new MemoryLimits(Memory.RUNTIME_MAX_PAGES + 1)));
    }
}
