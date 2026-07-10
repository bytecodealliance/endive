package run.endive.redline.api.internal;

import java.util.HashMap;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;

/**
 * Builds canonical type maps for call_indirect type checking.
 * Structurally equal FunctionTypes get the same canonical index.
 */
public final class TypeMapUtils {

    private TypeMapUtils() {}

    public static int[] buildCanonicalTypeMap(WasmModule module) {
        var ts = module.typeSection();
        int count = ts.subTypeCount();
        int[] map = new int[count];
        var seen = new HashMap<FunctionType, Integer>();
        for (int i = 0; i < count; i++) {
            var type = ts.getType(i);
            if (type instanceof FunctionType) {
                FunctionType ft = (FunctionType) type;
                Integer canonical = seen.get(ft);
                if (canonical != null) {
                    map[i] = canonical;
                } else {
                    seen.put(ft, i);
                    map[i] = i;
                }
            } else {
                map[i] = i;
            }
        }
        return map;
    }
}
