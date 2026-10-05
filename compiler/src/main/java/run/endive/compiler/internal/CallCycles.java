package run.endive.compiler.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.OpCode;

/**
 * Finds the direct calls that can take part in unbounded execution without a backward branch.
 *
 * <p>A guest can only run without end by branching backwards, by calling through a table or a
 * reference, by calling into the host (which may call back), or by calling around a cycle of the
 * direct call graph (for example {@code f(n) = f(n - 1) + f(n - 1)}, whose depth stays small).
 * Direct calls to a function outside every cycle always return after a bounded amount of work
 * unless they branch backwards, call indirectly or call the host themselves, all of which are
 * checked, so they need no interruption check of their own.
 */
final class CallCycles {

    private CallCycles() {}

    /**
     * Returns, for each function index (imports first), whether a direct call to it must check
     * for thread interruption.
     */
    static boolean[] checkedCallees(WasmModule module) {
        int imports = module.importSection().count(ExternalType.FUNCTION);
        int defined = module.functionSection().functionCount();
        boolean[] checked = new boolean[imports + defined];
        Arrays.fill(checked, 0, imports, true);

        List<int[]> edges = new ArrayList<>(defined);
        for (int i = 0; i < defined; i++) {
            edges.add(directCallees(module, i, imports));
        }

        boolean[] cyclic = cyclicFunctions(edges);
        for (int i = 0; i < defined; i++) {
            checked[imports + i] = cyclic[i];
        }
        return checked;
    }

    private static int[] directCallees(WasmModule module, int function, int imports) {
        var body = module.codeSection().getFunctionBody(function);
        int[] callees = new int[8];
        int count = 0;
        for (var ins : body.instructions()) {
            if (ins.opcode() != OpCode.CALL && ins.opcode() != OpCode.RETURN_CALL) {
                continue;
            }
            int callee = (int) ins.operand(0) - imports;
            if (callee < 0) {
                continue;
            }
            if (count == callees.length) {
                callees = Arrays.copyOf(callees, count * 2);
            }
            callees[count++] = callee;
        }
        return Arrays.copyOf(callees, count);
    }

    /** Tarjan's strongly connected components, iterative so deep call graphs cannot overflow. */
    private static boolean[] cyclicFunctions(List<int[]> edges) {
        int count = edges.size();
        int[] index = new int[count];
        int[] lowLink = new int[count];
        boolean[] onStack = new boolean[count];
        boolean[] cyclic = new boolean[count];
        Arrays.fill(index, -1);

        int[] stack = new int[count];
        int stackSize = 0;
        int[] callStack = new int[count];
        int[] edgeCursor = new int[count];
        int nextIndex = 0;

        for (int root = 0; root < count; root++) {
            if (index[root] != -1) {
                continue;
            }
            int depth = 0;
            callStack[depth] = root;
            edgeCursor[root] = 0;
            index[root] = nextIndex;
            lowLink[root] = nextIndex;
            nextIndex++;
            stack[stackSize++] = root;
            onStack[root] = true;

            while (depth >= 0) {
                int node = callStack[depth];
                int[] callees = edges.get(node);
                if (edgeCursor[node] < callees.length) {
                    int callee = callees[edgeCursor[node]++];
                    if (callee == node) {
                        cyclic[node] = true;
                    }
                    if (index[callee] == -1) {
                        index[callee] = nextIndex;
                        lowLink[callee] = nextIndex;
                        nextIndex++;
                        stack[stackSize++] = callee;
                        onStack[callee] = true;
                        edgeCursor[callee] = 0;
                        callStack[++depth] = callee;
                    } else if (onStack[callee]) {
                        lowLink[node] = Math.min(lowLink[node], index[callee]);
                    }
                    continue;
                }

                if (lowLink[node] == index[node]) {
                    int size = 0;
                    int member;
                    int first = stackSize;
                    do {
                        member = stack[--stackSize];
                        onStack[member] = false;
                        size++;
                    } while (member != node);
                    if (size > 1) {
                        for (int i = stackSize; i < first; i++) {
                            cyclic[stack[i]] = true;
                        }
                    }
                }

                depth--;
                if (depth >= 0) {
                    int parent = callStack[depth];
                    lowLink[parent] = Math.min(lowLink[parent], lowLink[node]);
                }
            }
        }
        return cyclic;
    }
}
