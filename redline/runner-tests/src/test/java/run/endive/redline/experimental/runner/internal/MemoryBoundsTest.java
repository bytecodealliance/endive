package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.WasmRuntimeException;
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
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        assertThrows(WasmRuntimeException.class, () -> memory.readInt(PAGE));
        assertThrows(WasmRuntimeException.class, () -> memory.readLong(PAGE - 4));
        assertThrows(WasmRuntimeException.class, () -> memory.read(PAGE));
        assertThrows(WasmRuntimeException.class, () -> memory.readShort(PAGE - 1));
        assertThrows(WasmRuntimeException.class, () -> memory.readBytes(PAGE - 1, 8));
    }

    @Test
    public void writePastTheEndTraps() {
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        assertThrows(WasmRuntimeException.class, () -> memory.writeI32(PAGE, 1));
        assertThrows(WasmRuntimeException.class, () -> memory.writeLong(PAGE - 4, 1L));
        assertThrows(WasmRuntimeException.class, () -> memory.writeByte(PAGE, (byte) 1));
        assertThrows(WasmRuntimeException.class, () -> memory.writeShort(PAGE - 1, (short) 1));
    }

    @Test
    public void insideTheMemoryIsUntouched() {
        var memory = NativeMachineFactory.createMemory(new MemoryLimits(1, 2));
        memory.writeI32(PAGE - 4, 0x11223344);
        org.junit.jupiter.api.Assertions.assertEquals(0x11223344, memory.readInt(PAGE - 4));
    }
}
