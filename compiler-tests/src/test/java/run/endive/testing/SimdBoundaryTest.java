package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.internal.Compiler;
import run.endive.corpus.CorpusResources;
import run.endive.corpus.WatGenerator;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/** v128 values across calls, results, globals, exceptions and method splitting. */
public class SimdBoundaryTest {

    private static final WasmModule MODULE =
            Parser.parse(CorpusResources.getResource("compiled/simd_boundary.wat.wasm"));

    private static final long A = 0x0102030405060708L;
    private static final long B = -0x0111111111111111L;

    private static Instance instance(Function<Instance, Machine> machine) {
        var hostAdd =
                new HostFunction(
                        "env",
                        "host_add",
                        FunctionType.of(List.of(ValType.V128, ValType.V128), List.of(ValType.V128)),
                        (inst, args) -> new long[] {args[0] + args[2], args[1] + args[3]});
        var builder =
                Instance.builder(MODULE)
                        .withImportValues(ImportValues.builder().addFunction(hostAdd).build());
        if (machine != null) {
            builder.withMachineFactory(machine);
        }
        return builder.build();
    }

    private static void assertSameEverywhere(String export, long... args) {
        var expected = instance(null).export(export).apply(args);
        var compiled = instance(MachineFactoryCompiler::compile).export(export).apply(args);
        assertArrayEquals(expected, compiled, export);
        // $add, $pair, $pair_ref, $thrower, $double and $tail_indirect on the interpreter
        var mixed =
                instance(
                                MachineFactoryCompiler.builder(MODULE)
                                        .withInterpretedFunctions(Set.of(1, 3, 4, 6, 11, 13))
                                        .compile())
                        .export(export)
                        .apply(args);
        assertArrayEquals(expected, mixed, export);
    }

    @ParameterizedTest
    @ValueSource(strings = {"calls", "multi", "throw_catch", "tail_calls"})
    public void sameAsInterpreter(String export) {
        assertSameEverywhere(export, A, B);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    public void selectAndBrTable(int sel) {
        assertSameEverywhere("select_br", A, B, sel);
    }

    @Test
    public void expectedValues() {
        var compiled = instance(MachineFactoryCompiler::compile);
        assertArrayEquals(new long[] {3 * A, 3 * B}, compiled.export("calls").apply(A, B));
        assertArrayEquals(new long[] {3 * A + 8, 3 * B + 3}, compiled.export("multi").apply(A, B));
        assertArrayEquals(new long[] {A, B, 7}, compiled.export("throw_catch").apply(A, B));
        assertArrayEquals(new long[] {16 * A, 16 * B}, compiled.export("tail_calls").apply(A, B));
        assertArrayEquals(new long[] {B, A}, compiled.export("select_br").apply(A, B, 0));
        assertArrayEquals(
                new long[] {A + 100, B + 100}, compiled.export("select_br").apply(A, B, 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void splitFunction(boolean trampoline) {
        var module = Parser.parse(Wat2Wasm.parse(WatGenerator.simdSplit(trampoline)));
        var chunks = chunkDescriptors(module);
        assertFalse(chunks.isEmpty());
        // direct chunks take the parameters, a v128 as two longs; trampoline chunks the frame
        var prefix = trampoline ? "([J" : "(IJJ";
        assertTrue(chunks.stream().allMatch(d -> d.startsWith(prefix)), chunks::toString);
        var machine =
                MachineFactoryCompiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.FAIL)
                        .withMethodSplitting(true)
                        .compile();
        var expected = Instance.builder(module).build().export("run").apply(0, A, B);
        var actual =
                Instance.builder(module)
                        .withMachineFactory(machine)
                        .build()
                        .export("run")
                        .apply(0, A, B);
        assertArrayEquals(expected, actual);
    }

    private static List<String> chunkDescriptors(WasmModule module) {
        var result =
                Compiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.FAIL)
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
}
