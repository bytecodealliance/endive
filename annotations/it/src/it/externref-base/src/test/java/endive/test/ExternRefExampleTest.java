package endive.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import run.endive.annotations.WasmModuleInterface;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

class ExternRefExampleTest {

    @WasmModuleInterface("externref-example.wat.wasm")
    class TestModule implements TestModule_ModuleImports, TestModule_Env {
        private final Instance instance;

        private final TestModule_ModuleExports exports;

        public TestModule() {
            var module =
                    Parser.parse(
                            ExternRefExampleTest.class.getResourceAsStream(
                                    "/compiled/externref-example.wat.wasm"));
            this.instance = Instance.builder(module).withImportValues(toImportValues()).build();
            this.exports = new TestModule_ModuleExports(instance);
        }

        public TestModule_Env env() {
            return this;
        }

        public TestModule_ModuleExports exports() {
            return exports;
        }

        private Object sampleObj = null;

        public Object getHostObject() {
            sampleObj = new Object();
            return sampleObj;
        }

        public int isNull(Object arg0) {
            return (arg0 == null) ? 1 : 0;
        }
    }

    @Test
    public void testExternRef() {
        var module = new TestModule();

        assertEquals(1, module.exports().isNull(null));

        var hostObj = module.exports().getHostObject();
        assertNotNull(hostObj);

        assertEquals(0, module.exports().isNull(hostObj));
    }
}
