package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static run.endive.corpus.WatGenerator.wideResults;
import static run.endive.corpus.WatGenerator.wideResultsMixed;
import static run.endive.corpus.WatGenerator.wideResultsRef;
import static run.endive.corpus.WatGenerator.wideResultsSplit;

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

    // wasm-tools, as wabt has no call_ref and no default return_call
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
        var module = parse(wideResults(i32s, i64s));
        assertSameResults(interpreted(module), compiled(module));
    }

    @Test
    public void wideTupleFromInterpretedFunction() {
        var module = parse(wideResults(1, 128));
        var machine =
                MachineFactoryCompiler.builder(module)
                        .withInterpretedFunctions(Set.of(0))
                        .compile();
        assertSameResults(interpreted(module), compiled(module, machine));
    }

    @Test
    public void wideMixedNumericTuple() {
        var module = parse(wideResultsMixed(127));
        assertArrayEquals(
                interpreted(module).export("run").apply(), compiled(module).export("run").apply());
    }

    // a reference makes it an Object[]
    @Test
    public void wideTupleWithReference() {
        int count = 127;
        var module = parse(wideResultsRef(count));
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
        var module = parse(wideResultsSplit(128, trampoline));
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
