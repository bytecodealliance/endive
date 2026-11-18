package run.endive.wasm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.wasm.Parser;

public class ValTypeTest {

    @Test
    public void roundtrip() {
        var cases =
                new ValType[] {
                    ValType.F64,
                    ValType.F32,
                    ValType.I64,
                    ValType.I32,
                    ValType.V128,
                    ValType.FuncRef,
                    ValType.ExternRef,
                    ValType.builder()
                            .withOpcode(ValType.ID.RefNull)
                            .withTypeIdx(ValType.TypeIdxCode.FUNC.code())
                            .build(),
                    ValType.builder()
                            .withOpcode(ValType.ID.Ref)
                            .withTypeIdx(ValType.TypeIdxCode.EXTERN.code())
                            .build(),
                    ValType.builder().withOpcode(ValType.ID.Ref).withTypeIdx(16).build(),
                };

        for (var vt : cases) {
            long id = vt.id();
            ValType roundTrip = ValType.builder().fromId(id).build();
            assertEquals(vt, roundTrip);
        }
    }

    @Test
    public void noneIsSubtypeOfAnyHierarchy() {
        int none = ValType.TypeIdxCode.NONE.code();
        for (var ht :
                new ValType.TypeIdxCode[] {
                    ValType.TypeIdxCode.ANY,
                    ValType.TypeIdxCode.EQ,
                    ValType.TypeIdxCode.I31,
                    ValType.TypeIdxCode.STRUCT,
                    ValType.TypeIdxCode.ARRAY,
                }) {
            assertTrue(ValType.heapTypeSubtype(none, ht.code(), null), ht.name());
        }
        assertFalse(ValType.heapTypeSubtype(none, ValType.TypeIdxCode.FUNC.code(), null));
        assertFalse(ValType.heapTypeSubtype(none, ValType.TypeIdxCode.EXTERN.code(), null));
    }

    @Test
    public void checkExternRef() {
        var module =
                Parser.parse(CorpusResources.getResource("compiled/externref-example.wat.wasm"));

        assertEquals(3, module.typeSection().types().length);

        var type0 = module.typeSection().types()[0].returns().get(0);
        assertEquals(ValType.TypeIdxCode.EXTERN.code(), type0.typeIdx());
    }
}
