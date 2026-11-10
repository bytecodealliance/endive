package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.internal.Compiler;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Multivalue results are packed by a generated {@code value_xxx} method that takes every result
 * as a parameter. A result tuple wider than 255 JVM slots would exceed the descriptor limit, so
 * such tuples are packed inline instead.
 */
public class WideMultiValueReturnTest {

    private static final List<String> CALLERS =
            List.of("call", "call_indirect", "call_ref", "return_call");

    private static WasmModule parse(String wat) {
        return Parser.parse(Wat2Wasm.parse(wat));
    }

    private static Instance interpreted(WasmModule module) {
        return Instance.builder(module).build();
    }

    // compiled without falling back to the interpreter
    private static Instance compiled(WasmModule module) {
        assertTrue(Compiler.builder(module).build().compile().interpretedFunctions().isEmpty());
        return compiled(module, MachineFactoryCompiler::compile);
    }

    private static Instance compiled(WasmModule module, Function<Instance, Machine> machine) {
        return Instance.builder(module).withMachineFactory(machine).build();
    }

    private static String results(int i32s, int i64s) {
        return " i32".repeat(i32s) + " i64".repeat(i64s);
    }

    // $leaf returns i32s i32 and i64s i64 results, which each export gets in a different way
    private static WasmModule tupleModule(int i32s, int i64s) {
        var wat = new StringBuilder("(module (type $t (func (result");
        wat.append(results(i32s, i64s)).append("))) (table funcref (elem $leaf))");
        wat.append(" (func $leaf (type $t)").append(" i32.const 42".repeat(i32s));
        for (int i = 0; i < i64s; i++) {
            wat.append(" i64.const ").append(i - 64);
        }
        wat.append(")");
        wat.append(" (func (export \"call\") (type $t) call $leaf)");
        wat.append(
                " (func (export \"call_indirect\") (type $t) i32.const 0 call_indirect (type $t))");
        wat.append(" (func (export \"call_ref\") (type $t) ref.func $leaf call_ref $t)");
        wat.append(" (func (export \"return_call\") (type $t) return_call $leaf))");
        return parse(wat.toString());
    }

    private static void assertSameResults(Instance expected, Instance actual) {
        for (var caller : CALLERS) {
            assertArrayEquals(
                    expected.export(caller).apply(), actual.export(caller).apply(), caller);
        }
    }

    // 255 slots is the widest tuple value_xxx takes; 256, 257 and 261 are packed inline
    @ParameterizedTest
    @CsvSource({"1, 127", "0, 128", "1, 128", "1, 130"})
    public void wideI64Tuples(int i32s, int i64s) {
        var module = tupleModule(i32s, i64s);
        assertSameResults(interpreted(module), compiled(module));
    }

    @Test
    public void wideTupleFromInterpretedFunction() {
        var module = tupleModule(1, 128);
        var machine =
                MachineFactoryCompiler.builder(module)
                        .withInterpretedFunctions(Set.of(0))
                        .compile();
        assertSameResults(interpreted(module), compiled(module, machine));
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
        var module = parse(wat.toString());
        assertArrayEquals(
                interpreted(module).export("run").apply(), compiled(module).export("run").apply());
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
        Object marker = new Object();
        var expected =
                interpreted(module)
                        .export("run")
                        .applyWithRefs(new long[] {0}, new Object[] {marker});
        var actual =
                compiled(module).export("run").applyWithRefs(new long[] {0}, new Object[] {marker});
        assertArrayEquals(expected.longs(), actual.longs());
        assertSame(marker, expected.refResult(1));
        assertSame(marker, actual.refResult(1));
        assertEquals(5, actual.longResult(0));
        assertEquals(278, actual.longResult(count + 1));
    }

    // too large for one method: split at its blocks, or around a loop through the trampoline
    private static WasmModule splitModule(boolean loop) {
        int count = 128;
        var wat = new StringBuilder("(module (func $leaf (param $p i32) (result");
        wat.append(results(1, count)).append(") (local $acc i64)");
        if (loop) {
            wat.append(" (loop $l");
        }
        for (int block = 0; block < 8; block++) {
            wat.append(" (block (br_if 0 (local.get $p))");
            for (int i = 0; i < 200; i++) {
                wat.append(" (local.set $acc (i64.add (local.get $acc) (i64.const ")
                        .append(block * 1000 + i)
                        .append(")))");
            }
            wat.append(")");
        }
        if (loop) {
            wat.append(" (br_if $l (local.get $p)))");
        }
        wat.append(" i32.const 42");
        for (int i = 0; i < count; i++) {
            wat.append(" (i64.add (local.get $acc) (i64.const ").append(i).append("))");
        }
        wat.append(") (func (export \"run\") (param i32) (result").append(results(1, count));
        wat.append(") local.get 0 call $leaf))");
        return parse(wat.toString());
    }

    private static List<String> chunkDescriptors(WasmModule module) {
        var result =
                Compiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.SILENT)
                        .withMethodSplitting(true)
                        .build()
                        .compile();
        assertTrue(result.interpretedFunctions().isEmpty());
        var descriptors = new ArrayList<String>();
        for (byte[] bytes : result.classBytes().values()) {
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    if (name.startsWith("chunk")) {
                                        descriptors.add(descriptor);
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE);
        }
        return descriptors;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void wideTupleFromSplitFunction(boolean trampoline) {
        var module = splitModule(trampoline);
        var chunks = chunkDescriptors(module);
        assertFalse(chunks.isEmpty());
        // direct chunks take the parameters, trampoline chunks the long[] frame
        var prefix = trampoline ? "([J" : "(I";
        assertTrue(chunks.stream().allMatch(d -> d.startsWith(prefix)), chunks::toString);
        var machine =
                MachineFactoryCompiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.SILENT)
                        .withMethodSplitting(true)
                        .compile();
        assertArrayEquals(
                interpreted(module).export("run").apply(0),
                compiled(module, machine).export("run").apply(0));
    }
}
