package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static run.endive.compiler.internal.CompilerUtil.extractFuncId;
import static run.endive.compiler.internal.CompilerUtil.methodNameForFunc;
import static run.endive.compiler.internal.CompilerUtil.sanitizeWasmName;

import org.junit.jupiter.api.Test;

public class CompilerUtilTest {

    @Test
    public void methodNameWithoutNameSection() {
        assertEquals("func_0", methodNameForFunc(0, null));
        assertEquals("func_42", methodNameForFunc(42, null));
    }

    @Test
    public void sanitizeReplacesIllegalChars() {
        assertEquals("foo", sanitizeWasmName("foo"));
        assertEquals("a_b_c", sanitizeWasmName("a.b/c"));
        assertEquals("a_b_c_d_e_f", sanitizeWasmName("a.b;c[d<e>f"));
    }

    @Test
    public void sanitizePreservesUnderscoresAndDashes() {
        assertEquals("my_func", sanitizeWasmName("my_func"));
        assertEquals("my-func", sanitizeWasmName("my-func"));
    }

    @Test
    public void extractFuncIdFromSimpleName() {
        assertEquals(0, extractFuncId("func_0"));
        assertEquals(42, extractFuncId("func_42"));
    }

    @Test
    public void extractFuncIdFromNamedMethod() {
        assertEquals(0, extractFuncId("foo_0"));
        assertEquals(5, extractFuncId("my_func_5"));
    }

    @Test
    public void extractFuncIdReturnsNegativeForInvalid() {
        assertEquals(-1, extractFuncId("nounderscore"));
        assertEquals(-1, extractFuncId("func_abc"));
    }
}
