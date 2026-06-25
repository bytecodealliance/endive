package run.endive.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class WasmGcBuilderTest {

    @Test
    public void structBuilderNumericOnly() {
        var s =
                WasmStruct.builder()
                        .typeIdx(42)
                        .addField(100L)
                        .addField(200L)
                        .addField(300L)
                        .build();

        assertEquals(42, s.typeIdx());
        assertEquals(3, s.fieldCount());
        assertEquals(100L, s.field(0));
        assertEquals(200L, s.field(1));
        assertEquals(300L, s.field(2));
        assertNull(s.fieldRef(0));
    }

    @Test
    public void structBuilderMixed() {
        var ref = new Object();
        var s =
                WasmStruct.builder()
                        .typeIdx(7)
                        .addField(42L)
                        .addFieldRef(ref)
                        .addField(99L)
                        .build();

        assertEquals(3, s.fieldCount());
        assertEquals(42L, s.field(0));
        assertNull(s.fieldRef(0));
        assertEquals(0L, s.field(1));
        assertEquals(ref, s.fieldRef(1));
        assertEquals(99L, s.field(2));
        assertNull(s.fieldRef(2));
    }

    @Test
    public void structBuilderBulkArrays() {
        var s = WasmStruct.builder().typeIdx(1).fields(new long[] {10, 20}).build();

        assertEquals(2, s.fieldCount());
        assertEquals(10L, s.field(0));
        assertEquals(20L, s.field(1));
    }

    @Test
    public void arrayBuilderNumericOnly() {
        var a = WasmArray.builder().typeIdx(5).addElement(1L).addElement(2L).addElement(3L).build();

        assertEquals(5, a.typeIdx());
        assertEquals(3, a.length());
        assertEquals(1L, a.get(0));
        assertEquals(2L, a.get(1));
        assertEquals(3L, a.get(2));
        assertNull(a.getRef(0));
    }

    @Test
    public void arrayBuilderRefOnly() {
        var r1 = "hello";
        var r2 = "world";
        var a = WasmArray.builder().typeIdx(9).addElementRef(r1).addElementRef(r2).build();

        assertEquals(2, a.length());
        assertEquals(r1, a.getRef(0));
        assertEquals(r2, a.getRef(1));
        assertEquals(0L, a.get(0));
    }

    @Test
    public void arrayBuilderMixed() {
        var ref = new Object();
        var a =
                WasmArray.builder()
                        .typeIdx(3)
                        .addElement(77L)
                        .addElementRef(ref)
                        .addElement(88L)
                        .build();

        assertEquals(3, a.length());
        assertEquals(77L, a.get(0));
        assertNull(a.getRef(0));
        assertEquals(ref, a.getRef(1));
        assertEquals(88L, a.get(2));
    }

    @Test
    public void arrayBuilderBulkArrays() {
        var a = WasmArray.builder().typeIdx(2).elements(new long[] {5, 10, 15}).build();

        assertEquals(3, a.length());
        assertArrayEquals(new long[] {5, 10, 15}, a.elements());
    }
}
