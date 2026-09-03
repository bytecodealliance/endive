package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.MethodPrefixer;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

public class MethodPrefixerTest {

    @Test
    public void defaultUsesFuncPrefix() {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var result = Compiler.builder(module).build().compile();
        var methods = funcGroupMethods(result);

        assertTrue(
                methods.stream().anyMatch(n -> n.startsWith("func_")),
                "Expected the default \"func\" prefix, got: " + methods);
        assertFalse(
                methods.stream().anyMatch(n -> n.startsWith("foo_")),
                "Default mode should not produce named methods, got: " + methods);
    }

    @Test
    public void nameSectionPrefixerProducesNamedMethods() {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var result =
                Compiler.builder(module)
                        .withMethodPrefixer(MethodPrefixer.fromNameSection())
                        .build()
                        .compile();
        var methods = funcGroupMethods(result);

        assertTrue(
                methods.stream().anyMatch(n -> n.startsWith("foo_")),
                "Expected a method starting with 'foo_', got: " + methods);
    }

    @Test
    public void customPrefixerIsApplied() {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var result =
                Compiler.builder(module)
                        .withMethodPrefixer((funcId, m) -> "wasm")
                        .build()
                        .compile();
        var methods = funcGroupMethods(result);

        assertTrue(
                methods.stream().anyMatch(n -> n.startsWith("wasm_")),
                "Expected a method starting with 'wasm_', got: " + methods);
    }

    @Test
    public void everyMethodNameKeepsTheFuncIdSuffix() {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var result =
                Compiler.builder(module)
                        // a deliberately hostile prefixer: illegal characters, digits, underscores
                        .withMethodPrefixer((funcId, m) -> "a.b_1/c<9>")
                        .build()
                        .compile();

        var methods = funcGroupMethods(result);
        assertTrue(
                methods.stream().anyMatch(n -> n.startsWith("a_b_1_c_9_")),
                "No method used the sanitized prefix, got: " + methods);

        for (var name : methods) {
            if (!name.startsWith("a_b_1_c_9_")) {
                continue;
            }
            assertTrue(
                    CompilerUtil.extractFuncId(name) >= 0,
                    "Could not recover the func id from: " + name);
        }
    }

    @Test
    public void namedMethodsExecuteCorrectly() {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var instance =
                Instance.builder(module)
                        .withMachineFactory(
                                MachineFactoryCompiler.builder(module)
                                        .withMethodPrefixer(MethodPrefixer.fromNameSection())
                                        .compile())
                        .build();

        var function = instance.export("foo");
        assertArrayEquals(new long[] {42}, function.apply(0));
        assertArrayEquals(new long[] {99}, function.apply(1));
    }

    private static List<String> funcGroupMethods(CompilerResult result) {
        var methods = new ArrayList<String>();
        for (var entry : result.classBytes().entrySet()) {
            if (!entry.getKey().contains("FuncGroup")) {
                continue;
            }
            var reader = new ClassReader(entry.getValue());
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(
                                int access,
                                String name,
                                String descriptor,
                                String signature,
                                String[] exceptions) {
                            methods.add(name);
                            return null;
                        }
                    },
                    0);
        }
        return methods;
    }
}
