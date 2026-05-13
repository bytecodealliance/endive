package endive.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import run.endive.annotations.WasmModuleInterface;
import run.endive.runtime.Instance;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;

class HelloWasiTest {

    @WasmModuleInterface("hello-wasi.wat.wasm")
    class TestModule implements TestModule_ModuleImports, TestModule_WasiSnapshotPreview1 {
        private final Instance instance;
        private final TestModule_ModuleExports exports;
        private final WasiPreview1 wasi;
        public final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        public TestModule() {
            wasi =
                    WasiPreview1.builder()
                            .withOptions(WasiOptions.builder().withStdout(baos).build())
                            .build();
            var module =
                    Parser.parse(HelloWasiTest.class.getResourceAsStream("/hello-wasi.wat.wasm"));

            this.instance =
                    Instance.builder(module)
                            .withImportValues(toImportValues())
                            .withStart(false) // Needed to avoid circular references of instance
                            .build();

            this.exports = new TestModule_ModuleExports(instance);
        }

        public TestModule_ModuleExports exports() {
            return exports;
        }

        @Override
        public TestModule_WasiSnapshotPreview1 wasiSnapshotPreview1() {
            return this;
        }

        @Override
        public int fdWrite(int fd, int iovs, int iovsLen, int nwrittenPtr) {
            return wasi.fdWrite(instance.memory(), fd, iovs, iovsLen, nwrittenPtr);
        }
    }

    @Test
    public void helloWasiModule() {
        // Arrange
        var helloWasiModule = new TestModule();

        // Act
        helloWasiModule.exports()._start();

        // Assert
        assertEquals("hello world\n", helloWasiModule.baos.toString(UTF_8));
    }
}
