package endive.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;

/** QuickJS has functions too large for the JVM to JIT-compile, which the plugin splits. */
public class MethodSplittingTest {

    @Test
    public void splitQuickJsRunsJavaScript() {
        var stderr = new ByteArrayOutputStream();
        var wasi =
                WasiPreview1.builder()
                        .withOptions(WasiOptions.builder().withStderr(stderr).build())
                        .build();
        var quickjs =
                Instance.builder(QuickJs.load())
                        .withMachineFactory(QuickJs::create)
                        .withImportValues(
                                ImportValues.builder().addFunction(wasi.toHostFunctions()).build())
                        .build();
        var store = new Store().register("javy_quickjs_provider_v1", quickjs);

        var hello =
                Parser.parse(
                        MethodSplittingTest.class.getResourceAsStream(
                                "/hello-world.js.javy-dynamic.wasm"));
        Instance.builder(hello).withImportValues(store.toImportValues()).build();

        assertEquals("Hello world dynamic Javy!\n", stderr.toString(UTF_8));
    }
}
