package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.internal.Compiler;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Multivalue results are packed by a generated {@code value_xxx} method that takes every result
 * as a parameter. A result tuple wider than 255 JVM slots would exceed the descriptor limit, so
 * such tuples are packed inline instead.
 */
public class WideMultiValueReturnTest {

    private static WasmModule parse(String wat) {
        return Parser.parse(Wat2Wasm.parse(wat));
    }

    private static void assertSameResults(WasmModule module) {
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        assertArrayEquals(interpreted.export("run").apply(), compiled.export("run").apply());
    }

    // One i32 plus count i64 results: 1 + 2 * count slots. 127 is the widest tuple that still
    // fits the packing method (255 slots); 128 and 130 need inline packing.
    @Test
    public void wideI64Tuples() {
        for (int count : new int[] {127, 128, 130}) {
            var wat = new StringBuilder("(module (func $leaf (result i32");
            wat.append(" i64".repeat(count)).append(") i32.const 42");
            for (int i = 0; i < count; i++) {
                wat.append(" i64.const ").append(i - 64);
            }
            wat.append(") (func (export \"run\") (result i32");
            wat.append(" i64".repeat(count)).append(") call $leaf))");
            var module = parse(wat.toString());
            assertTrue(Compiler.builder(module).build().compile().interpretedFunctions().isEmpty());
            assertSameResults(module);
        }
    }

    // Floating-point results are stored as their raw bits, as with the packing method.
    @Test
    public void wideMixedNumericTuple() {
        int count = 127;
        var wat = new StringBuilder("(module (func $leaf (result i32 f32 f64");
        wat.append(" i64".repeat(count))
                .append(") i32.const -7 f32.const -1.5 f64.const nan:0x4000000000001");
        for (int i = 0; i < count; i++) {
            wat.append(" i64.const ").append(Long.MIN_VALUE + i);
        }
        wat.append(") (func (export \"run\") (result i32 f32 f64");
        wat.append(" i64".repeat(count)).append(") call $leaf))");
        assertSameResults(parse(wat.toString()));
    }

    // A wide result tuple that includes a reference uses the Object[] representation and must
    // keep the reference's identity.
    @Test
    public void wideTupleWithReference() {
        int count = 127;
        var wat = new StringBuilder("(module (func $leaf (param externref) (result i32 externref");
        wat.append(" i64".repeat(count)).append(") i32.const 5 local.get 0");
        for (int i = 0; i < count; i++) {
            wat.append(" i64.const ").append(i * 3 - 100);
        }
        wat.append(") (func (export \"run\") (param externref) (result i32 externref");
        wat.append(" i64".repeat(count)).append(") local.get 0 call $leaf))");
        var module = parse(wat.toString());
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        Object marker = new Object();
        var expected =
                interpreted.export("run").applyWithRefs(new long[] {0}, new Object[] {marker});
        var actual = compiled.export("run").applyWithRefs(new long[] {0}, new Object[] {marker});
        assertArrayEquals(expected.longs(), actual.longs());
        assertSame(marker, expected.refResult(1));
        assertSame(marker, actual.refResult(1));
        assertEquals(5, actual.longResult(0));
        assertEquals(278, actual.longResult(count + 1));
    }
}
