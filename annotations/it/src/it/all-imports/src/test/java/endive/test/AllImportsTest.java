package endive.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static run.endive.wasm.types.Value.REF_NULL_VALUE;

import org.junit.jupiter.api.Test;
import run.endive.annotations.WasmModuleInterface;
import run.endive.runtime.ByteBufferMemory;
import run.endive.runtime.GlobalInstance;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.Parser;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.Value;
import run.endive.wasm.types.ValueType;

class AllImportsTest {

    @WasmModuleInterface("all-imports.wat.wasm")
    class TestModule implements TestModule_ModuleImports, TestModule_Env {
        private final Instance instance;
        private final TestModule_ModuleExports exports;
        private final Memory memory;
        private final Table table;
        public boolean func3Invoked;
        public boolean func6Invoked;

        public TestModule() {
            memory = new ByteBufferMemory(new MemoryLimits(1, 2));
            table = new Table(ValueType.FuncRef, new TableLimits(10, 20));
            var module =
                    Parser.parse(AllImportsTest.class.getResourceAsStream("/all-imports.wat.wasm"));

            instance = Instance.builder(module).withImportValues(toImportValues()).build();
            exports = new TestModule_ModuleExports(instance);
        }

        public TestModule_ModuleExports exports() {
            return exports;
        }

        @Override
        public TestModule_Env env() {
            return this;
        }

        @Override
        public TestModule_Funcs funcs() {
            return new TestModule_Funcs() {
                @Override
                public double fun1(int arg0) {
                    return arg0 * 2.0;
                }

                @Override
                public double fun2(double arg0) {
                    return arg0 * 2;
                }

                @Override
                public void fun3(int arg0, double arg1) {
                    func3Invoked = true;
                }

                @Override
                public double fun4(int arg0, double arg1) {
                    return arg1 + arg0;
                }

                @Override
                public long[] fun5(int arg0, double arg1) {
                    return new long[] {(long) arg0, Value.doubleToLong(arg1 * 2)};
                }

                @Override
                public void fun6() {
                    func6Invoked = true;
                }
            };
        }

        @Override
        public Memory memory() {
            return memory;
        }

        @Override
        public TableInstance table() {
            return new TableInstance(table, REF_NULL_VALUE);
        }

        @Override
        public GlobalInstance global1() {
            return new GlobalInstance(Value.i32(1));
        }

        @Override
        public GlobalInstance global2() {
            return new GlobalInstance(Value.i64(2));
        }

        @Override
        public GlobalInstance global3() {
            return new GlobalInstance(Value.f32(3));
        }

        @Override
        public GlobalInstance global4() {
            return new GlobalInstance(Value.f64(4));
        }
    }

    @Test
    public void allImportsModule() {
        // Arrange
        var allImportsModule = new TestModule();

        assertEquals(1, allImportsModule.global1().getValue());
        assertEquals(2L, allImportsModule.global2().getValue());
        assertEquals(3.0f, allImportsModule.global3().getValue());
        assertEquals(4.0d, allImportsModule.global4().getValue());
        assertEquals(4.0d, allImportsModule.exports().fun1(2));
        assertEquals(6.0d, allImportsModule.exports().fun2(3.0d));
        assertFalse(allImportsModule.func3Invoked);
        allImportsModule.exports().fun3(0, 0.0);
        assertTrue(allImportsModule.func3Invoked);
        assertEquals(8.0d, allImportsModule.exports().fun2(4.0d));
        var fun5Result = allImportsModule.exports().fun5(1, 2.0);
        assertEquals(2, fun5Result.length);
        assertEquals(1L, fun5Result[0]);
        assertEquals(4.0d, Value.longToDouble(fun5Result[1]));
        assertFalse(allImportsModule.func6Invoked);
        allImportsModule.exports().fun6();
        assertTrue(allImportsModule.func6Invoked);
        assertNotNull(allImportsModule.memory());
        assertNotNull(allImportsModule.table());
    }
}
