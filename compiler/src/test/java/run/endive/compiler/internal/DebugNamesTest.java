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
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

public class DebugNamesTest {

    @Test
    public void defaultDoesNotUseDebugNames() {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var result = Compiler.builder(module).build().compile();
        var methods = funcGroupMethods(result);

        assertFalse(
                methods.stream().anyMatch(n -> n.startsWith("foo_")),
                "Default mode should not produce named methods, got: " + methods);
    }

    @Test
    public void debugNamesProduceNamedMethods() {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var result = Compiler.builder(module).withUseDebugNames(true).build().compile();
        var methods = funcGroupMethods(result);

        assertTrue(
                methods.stream().anyMatch(n -> n.startsWith("foo_")),
                "Expected a method starting with 'foo_', got: " + methods);
    }

    @Test
    public void debugNamesExecuteCorrectly() throws InterruptedException {
        var module = Parser.parse(CorpusResources.getResource("compiled/branching.wat.wasm"));
        var instance =
                Instance.builder(module)
                        .withMachineFactory(
                                MachineFactoryCompiler.builder(module)
                                        .withUseDebugNames(true)
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
