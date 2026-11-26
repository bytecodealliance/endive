package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import run.endive.runtime.OpCodeIdentifier;
import run.endive.runtime.internal.V128Words;
import run.endive.wasm.types.OpCode;

public class SimdOpTest {

    @Test
    public void everyV128OpcodeHasASimdOp() {
        var v128 = EnumSet.noneOf(OpCode.class);
        for (var op : OpCode.values()) {
            if (SimdOp.isV128(op)) {
                v128.add(op);
            }
        }
        var listed = EnumSet.noneOf(OpCode.class);
        for (var simd : SimdOp.all()) {
            listed.add(simd.op);
        }

        var missing = EnumSet.copyOf(v128);
        missing.removeAll(listed);
        assertTrue(missing.isEmpty(), "v128 opcodes missing from SimdOp: " + missing);
        listed.removeAll(v128);
        assertTrue(listed.isEmpty(), "not v128 opcodes: " + listed);
    }

    @Test
    public void everyHelperCallHasOneV128WordsHelper() {
        var helpers = new ArrayList<OpCode>();
        for (var m : V128Words.class.getMethods()) {
            var id = m.getAnnotation(OpCodeIdentifier.class);
            if (id != null) {
                helpers.add(id.value());
            }
        }
        var calls = EnumSet.noneOf(OpCode.class);
        for (var simd : SimdOp.all()) {
            if (V128Emitter.callsHelper(simd)) {
                calls.add(simd.op);
            }
        }

        var helperOps = EnumSet.copyOf(helpers);
        assertEquals(helpers.size(), helperOps.size(), "an opcode with two V128Words helpers");
        var missing = EnumSet.copyOf(calls);
        missing.removeAll(helperOps);
        assertTrue(missing.isEmpty(), "V128Words helpers missing: " + missing);
        helperOps.removeAll(calls);
        assertTrue(helperOps.isEmpty(), "V128Words helpers never called: " + helperOps);
    }
}
