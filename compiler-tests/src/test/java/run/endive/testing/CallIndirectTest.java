package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;

public class CallIndirectTest {

    private static final int FUNC_COUNT = 1100;

    @Test
    public void callsManyFunctionsOfOneType() {
        var instance =
                Instance.builder(Parser.parse(Wat2Wasm.parse(wat())))
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        for (int k : new int[] {0, 1, 511, 512, 600, FUNC_COUNT - 1}) {
            long result = instance.export("call").apply(k, 1_000_000_000_000L, 7)[0];
            assertEquals(1_000_000_000_000L + 7 + k, result);
        }
    }

    // FUNC_COUNT functions of type (i64, i32) -> i64, each returning p0 + p1 + its index.
    private static String wat() {
        var sb = new StringBuilder("(module\n");
        sb.append("  (type $t (func (param i64 i32) (result i64)))\n");
        sb.append("  (table ").append(FUNC_COUNT).append(" funcref)\n");
        sb.append("  (elem (i32.const 0)");
        for (int k = 0; k < FUNC_COUNT; k++) {
            sb.append(" $f").append(k);
        }
        sb.append(")\n");
        for (int k = 0; k < FUNC_COUNT; k++) {
            sb.append("  (func $f")
                    .append(k)
                    .append(" (type $t) local.get 0 local.get 1 i64.extend_i32_u i64.add")
                    .append(" i64.const ")
                    .append(k)
                    .append(" i64.add)\n");
        }
        sb.append("  (func (export \"call\") (param i32 i64 i32) (result i64)\n");
        sb.append("    local.get 1 local.get 2 local.get 0 call_indirect (type $t))\n");
        sb.append(")\n");
        return sb.toString();
    }
}
