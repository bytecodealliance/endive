package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static run.endive.compiler.internal.CompilerUtil.extractFuncId;
import static run.endive.compiler.internal.CompilerUtil.methodNameForFunc;
import static run.endive.compiler.internal.CompilerUtil.sanitizeWasmName;

import org.junit.jupiter.api.Test;
import run.endive.compiler.MethodPrefixer;

public class CompilerUtilTest {

    @Test
    public void methodNameWithoutPrefixer() {
        assertEquals("func_0", methodNameForFunc(0, null, null));
        assertEquals("func_42", methodNameForFunc(42, null, null));
    }

    @Test
    public void methodNameUsesPrefix() {
        MethodPrefixer prefixer = (funcId, module) -> "foo";
        assertEquals("foo_0", methodNameForFunc(0, prefixer, null));
        assertEquals("foo_42", methodNameForFunc(42, prefixer, null));
    }

    @Test
    public void methodNameSanitizesPrefix() {
        MethodPrefixer prefixer = (funcId, module) -> "a.b/c";
        assertEquals("a_b_c_7", methodNameForFunc(7, prefixer, null));
    }

    @Test
    public void methodNameFallsBackToDefaultPrefix() {
        assertEquals("func_3", methodNameForFunc(3, (funcId, module) -> null, null));
        assertEquals("func_3", methodNameForFunc(3, (funcId, module) -> "", null));
    }

    @Test
    public void percentEncodedPrefixSurvivesSanitization() {
        // A prefixer that encodes the illegal characters itself keeps the name reversible.
        MethodPrefixer prefixer = (funcId, module) -> "core%2Efmt%2FFormatter";
        assertEquals("core%2Efmt%2FFormatter_9", methodNameForFunc(9, prefixer, null));
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
        assertEquals(9, extractFuncId("core%2Efmt%2FFormatter_9"));
    }

    @Test
    public void extractFuncIdReturnsNegativeForInvalid() {
        assertEquals(-1, extractFuncId("nounderscore"));
        assertEquals(-1, extractFuncId("func_abc"));
    }
}
