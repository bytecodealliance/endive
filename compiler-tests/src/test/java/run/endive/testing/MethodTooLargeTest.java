package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Type.getInternalName;
import static run.endive.corpus.WatGenerator.methodTooLarge;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.approvaltests.Approvals;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.internal.Compiler;
import run.endive.compiler.internal.Shaded;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;

public class MethodTooLargeTest {

    @Test
    public void testBigFunc() {
        String wat = methodTooLarge(20_000);
        byte[] wasm = Wat2Wasm.parse(wat);

        var module = Parser.parse(wasm);
        var result = Compiler.builder(module).build().compile();

        // We only verify that the resulting class contains the fallback to interpreter
        verifyClass(result.classBytes(), true);
    }

    @Test
    public void testFuncWithFarJumpsAndManyLocals() {
        var wat = new StringBuilder("(module (func (param i32) (local");
        wat.append(" i32".repeat(1500)).append(") (block (br_if 0 (local.get 0))");
        for (int i = 1; i <= 3000; i++) {
            int local = (i % 1500) + 1;
            wat.append(
                    String.format(
                            " (local.set %d (i32.add (local.get %d) (i32.const %d)))",
                            local, local, i));
        }
        wat.append(")))");
        var module = Parser.parse(Wat2Wasm.parse(wat.toString()));

        var result =
                Compiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.FAIL)
                        .build()
                        .compile();

        assertTrue(result.interpretedFunctions().isEmpty());
    }

    private static void verifyClass(Map<String, byte[]> classBytes, boolean skipMethodsClss) {
        var writer = new StringWriter();

        for (byte[] bytes : classBytes.values()) {
            ClassReader cr = new ClassReader(bytes);
            if (skipMethodsClss && cr.getClassName().endsWith("Methods")) {
                continue;
            }
            cr.accept(
                    new ClassVisitor(Opcodes.ASM9, new TraceClassVisitor(new PrintWriter(writer))) {
                        @Override
                        public void visit(
                                int version,
                                int access,
                                String name,
                                String signature,
                                String superName,
                                String[] interfaces) {
                            if (name.endsWith("CompiledMachineFuncGroup_0")) {
                                super.visit(
                                        version, access, name, signature, superName, interfaces);
                            }
                        }

                        @Override
                        public MethodVisitor visitMethod(
                                int access,
                                String name,
                                String descriptor,
                                String signature,
                                String[] exceptions) {
                            if (name.equals("func_2")) {
                                return super.visitMethod(
                                        access, name, descriptor, signature, exceptions);
                            }
                            return null;
                        }
                    },
                    0);
            writer.append("\n");
        }

        String output = writer.toString();
        output = output.replaceAll("(?m)^ {3}FRAME.*\\n", "");
        output = output.replaceAll("(?m)^ {4}MAX(STACK|LOCALS) = \\d+\\n", "");
        output = output.replaceAll("(?m)^ {4}(LINENUMBER|LOCALVARIABLE) .*\\n", "");
        output = output.replaceAll("(?m)^ *// .*\\n", "");
        output = output.replaceAll("(?m)^ *.*INNERCLASS.*\\n", "");
        output = output.replaceAll("\\n{3,}", "\n\n");
        output = output.strip() + "\n";

        Approvals.verify(output);

        assertFalse(
                output.contains(getInternalName(Shaded.class)),
                "Class contains non-inlined reference to " + Shaded.class.getName());
    }
}
