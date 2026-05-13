package run.endive.wasm;

import static run.endive.wasm.Parser.parseWithoutDecoding;
import static run.endive.wasm.ParserTest.wasmCorpusFiles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import run.endive.wasm.types.RawSection;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

public class WasmWriterTest {

    @Test
    public void shouldRoundTrip() throws IOException {
        for (var file : wasmCorpusFiles()) {
            // uses non-canonical size encodings
            if (file.getName().endsWith("main.go.wasm")) {
                continue;
            }

            byte[] wasm = Files.readAllBytes(file.toPath());
            var writer = new WasmWriter();
            parseWithoutDecoding(wasm, section -> writer.writeSection((RawSection) section));
            Parser.parse(writer.bytes());
            assertArrayEquals(wasm, writer.bytes());
        }
    }
}
