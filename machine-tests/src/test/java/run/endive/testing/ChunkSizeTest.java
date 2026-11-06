package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.internal.Compiler;
import run.endive.corpus.CorpusResources;
import run.endive.wasm.Parser;

public class ChunkSizeTest {

    // HotSpot's HugeMethodLimit
    private static final int HUGE_METHOD_LIMIT = 8000;

    @Test
    public void quickJsChunksFitHugeMethodLimit() {
        var module =
                Parser.parse(
                        CorpusResources.getResource("compiled/quickjs-provider.javy-dynamic.wasm"));
        var classes =
                Compiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.SILENT)
                        .withMethodSplitting(true)
                        .build()
                        .compile()
                        .classBytes();

        int chunks = 0;
        var oversized = new ArrayList<String>();
        for (byte[] bytes : classes.values()) {
            for (var method : ClassFile.of().parse(bytes).methods()) {
                var name = method.methodName().stringValue();
                if (name.startsWith("chunk")) {
                    chunks++;
                    int size = method.findAttribute(Attributes.code()).orElseThrow().codeLength();
                    if (size > HUGE_METHOD_LIMIT) {
                        oversized.add(name + " " + size);
                    }
                }
            }
        }
        assertTrue(chunks > 0);
        assertEquals(List.of(), oversized);
    }
}
