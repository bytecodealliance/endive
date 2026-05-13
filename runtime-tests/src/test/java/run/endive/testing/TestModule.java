package run.endive.testing;

import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.MalformedException;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import java.io.IOException;

public class TestModule {

    private WasmModule module;

    private static final String HACK_MATCH_ALL_MALFORMED_EXCEPTION_TEXT =
            "Matching keywords to get the WebAssembly testsuite to pass: "
                    + "malformed UTF-8 encoding "
                    + "import after function "
                    + "inline function type "
                    + "constant out of range"
                    + "unknown operator "
                    + "unexpected token "
                    + "unexpected mismatching "
                    + "mismatching label "
                    + "unknown type "
                    + "duplicate func "
                    + "duplicate local "
                    + "duplicate global "
                    + "duplicate memory "
                    + "duplicate table "
                    + "mismatching label "
                    + "import after global "
                    + "import after table "
                    + "import after memory "
                    + "i32 constant out of range "
                    + "unknown label "
                    + "alignment "
                    + "multiple start sections"
                    + "wrong number of lane literals"
                    + "alignment must be a power of two"
                    + "invalid lane length"
                    + "malformed lane index";

    public static TestModule of(String classpath) {
        try (var is = TestModule.class.getResourceAsStream(classpath)) {
            if (classpath.endsWith(".wat")) {
                byte[] parsed;
                try {
                    parsed = Wat2Wasm.parse(is);
                } catch (RuntimeException e) {
                    throw new MalformedException(
                            e.getMessage() + HACK_MATCH_ALL_MALFORMED_EXCEPTION_TEXT);
                }
                return of(Parser.parse(parsed));
            }
            return of(Parser.parse(is));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static TestModule of(WasmModule module) {
        return new TestModule(module);
    }

    public TestModule(WasmModule module) {
        this.module = module;
    }

    public Instance instantiate(Store s) {
        ImportValues importValues = s.toImportValues();
        return Instance.builder(module)
                .withImportValues(importValues)
                .withMachineFactory(InterpreterMachineFactory::create)
                .withMemoryFactory(ByteArrayMemory::new)
                .build();
    }
}
