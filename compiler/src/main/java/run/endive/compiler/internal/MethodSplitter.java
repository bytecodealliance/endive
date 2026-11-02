package run.endive.compiler.internal;

import static java.lang.invoke.MethodType.methodType;
import static org.objectweb.asm.Type.INT_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;
import static org.objectweb.asm.Type.getType;
import static org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE;
import static run.endive.compiler.internal.Compiler.emitFunction;
import static run.endive.compiler.internal.Compiler.emitInstruction;
import static run.endive.compiler.internal.Compiler.emitLocalsDefaultInit;
import static run.endive.compiler.internal.CompilerUtil.asmType;
import static run.endive.compiler.internal.CompilerUtil.emitInvokeStatic;
import static run.endive.compiler.internal.CompilerUtil.emitJvmToLong;
import static run.endive.compiler.internal.CompilerUtil.emitLongToJvm;
import static run.endive.compiler.internal.CompilerUtil.hasTooManyParameters;
import static run.endive.compiler.internal.CompilerUtil.jvmParameterTypes;
import static run.endive.compiler.internal.CompilerUtil.jvmReturnType;
import static run.endive.compiler.internal.CompilerUtil.jvmType;
import static run.endive.compiler.internal.CompilerUtil.localType;
import static run.endive.compiler.internal.CompilerUtil.methodTypeFor;
import static run.endive.compiler.internal.CompilerUtil.slotCount;
import static run.endive.compiler.internal.ShadedRefs.CHECK_INTERRUPTION;

import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.types.FunctionBody;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/**
 * Splits functions too large for HotSpot to JIT into smaller methods. See
 * compiler/METHOD_SPLITTING.md.
 */
final class MethodSplitter {

    private MethodSplitter() {}

    /** Where to split one function: either a direct split ({@code cuts}) or a trampoline. */
    static final class Plan {
        final int totalSize;
        // direct split: the last instruction index of every chunk but the last, or null
        final int[] cuts;
        // trampoline split: the first instruction index of every chunk, or null
        final int[] trampolineStarts;
        // trampoline chunk start -> operand-stack types spilled into it
        final Map<Integer, Type[]> entryStacks;

        private Plan(
                int totalSize,
                int[] cuts,
                int[] trampolineStarts,
                Map<Integer, Type[]> entryStacks) {
            this.totalSize = totalSize;
            this.cuts = cuts;
            this.trampolineStarts = trampolineStarts;
            this.entryStacks = entryStacks;
        }

        boolean isTrampoline() {
            return trampolineStarts != null;
        }

        int chunkCount() {
            return isTrampoline() ? trampolineStarts.length : cuts.length + 1;
        }
    }

    private static final Type LONG_ARRAY_TYPE = Type.getType(long[].class);

    // Functions with fewer instructions than this are never measured.
    private static final int LARGE_FUNCTION_INSTRUCTION_THRESHOLD = 200;

    // A JVM method's Code attribute cannot exceed 65535 bytes, so a chunk above
    // that is not merely un-JITable but unloadable. Clamp well under it: the
    // planner's own size figures are estimates, so leave headroom rather than
    // emitting a method the class writer will reject.
    private static final int JVM_METHOD_SIZE_CEILING = 48000;

    // The size a function body has to stay under to be worth JITing is the same
    // ceiling HUGE_METHOD_LIMIT already expresses for dispatch methods, so reuse it
    // rather than introducing a second notion of "too big to JIT" with its own
    // number and its own knob: endive.hugeMethodLimit now tunes both together.
    private static final int JIT_ELIGIBILITY_SIZE_BYTES =
            Math.min(Compiler.HUGE_METHOD_LIMIT, JVM_METHOD_SIZE_CEILING);

    // A single br_table with targets scattered across a function creates spans that
    // collectively block almost every candidate (all targets share one source
    // position -- the switch itself). For switches with at least this many distinct
    // targets, and where EVERY distinct target is independently safe to become its
    // own chunk boundary (not a loop header, stack-depth-0, not blocked by any
    // OTHER branch's span), those targets are added to the regular candidate pool
    // -- exempted only from this one switch's own spans, nothing else's. The
    // existing size-driven greedy packer then decides, same as always, whether to
    // actually cut there. Below this threshold the complexity isn't worth it: a
    // small switch's whole span is cheap to just keep intact.
    private static final int SWITCH_SPLIT_MIN_TARGETS = 8;

    // Linear chunking can never cut inside a loop, because a loop's back-edge is a
    // JVM `goto` and a goto cannot cross a method boundary. That single restriction
    // is what leaves the largest real functions interpreted: measured on real code,
    // 97.5% of QuickJS's JS_CallInternal and 98.2% of printf_core sits inside a
    // loop, so essentially no position in either is a legal cut.
    //
    // Trampoline splitting removes the restriction by replacing goto-based control
    // flow *between* chunks with a driver loop. Every Wasm local lives in a long[]
    // frame; each chunk is a method that unboxes the frame into JVM slots, runs its
    // slice, then writes the next chunk's index into the frame's PC slot and
    // returns. The driver in the entry method reads the PC and calls the next
    // chunk. A back-edge is then just another PC value, so loops stop mattering
    // entirely -- and because a chunk sets PC=DONE on entry, a real Wasm `return`
    // stays an ordinary areturn and every existing opcode emitter is untouched.
    //
    // Dispatch-loop functions inherently produce roughly one chunk per switch case
    // (every case body is a branch target from the dispatch chunk, so it has to be
    // its own chunk entry). That is fine and even desirable -- it is what a
    // hand-written Java interpreter looks like -- but cap it so a pathological
    // function can't emit an unbounded number of methods into one class.
    private static final int TRAMPOLINE_MAX_CHUNKS = 4096;

    // PC value meaning "this chunk returned normally; the value it returned is the
    // function's real result". Chunks store it on entry, so only an explicit
    // cross-chunk transition ever overwrites it.
    private static final int TRAMPOLINE_PC_DONE = -1;

    // Fixed per-chunk cost: setting the PC on entry, the shared epilogue's PC
    // store, and the dummy return. Over-estimated on purpose.
    private static final int TRAMPOLINE_CHUNK_BASE_COST = 64;

    // Cost of syncing one local in one direction (aload frame, push index, array
    // load/store, the long<->JVM-type conversion, and the slot load/store). Also
    // over-estimated: the widest of those forms is about 10 bytes.
    private static final int TRAMPOLINE_LOCAL_SYNC_COST = 10;

    // How many pending operand-stack values a chunk boundary may carry. A boundary
    // is a method boundary, so anything on the stack has to be spilled into the
    // frame and reloaded on the other side; the cap keeps that bounded. Depths at
    // real branch targets are small (0-3), so this is generous.
    private static final int TRAMPOLINE_MAX_SPILL = 8;

    // The JVM limit on argument slots of a static method.
    private static final int JVM_MAX_ARGUMENT_SLOTS = 255;

    /**
     * Plans how to split the function compiled by {@code ctx}, or returns {@code null} when it
     * already fits a JIT-eligible method or cannot be split safely.
     */
    static Plan plan(Context ctx, List<CompilerInstruction> instructions) {
        if (instructions.size() <= LARGE_FUNCTION_INSTRUCTION_THRESHOLD) {
            return null;
        }
        var type = ctx.getType();
        var body = ctx.getBody();
        boolean eligible =
                !hasTooManyParameters(type)
                        && !type.hasObjectRefParams()
                        && !type.hasObjectRefReturns()
                        && body.localTypes().stream().noneMatch(ValType::isObjectRef);
        if (!eligible) {
            return null;
        }
        // Linear and switch chunking still decline try_table outright. The
        // trampoline can take it, because it treats each protected range as a
        // region no boundary may fall inside -- see planTrampolineSplit.
        boolean directEligible =
                fitsChunkArguments(type, body)
                        && instructions.stream()
                                .noneMatch(ins -> ins.opcode() == CompilerOpCode.TRY_CATCH_BLOCK);

        var direct = analyzeAndPlanSplit(ctx, type, body, instructions, directEligible);
        if (direct.totalSize <= JIT_ELIGIBILITY_SIZE_BYTES) {
            return null;
        }
        if (direct.cuts != null) {
            return direct;
        }
        // Linear chunking couldn't place a cut -- almost always because the body
        // is dominated by a loop. Try the trampoline, which isn't subject to
        // that restriction.
        return planTrampolineSplit(ctx, type, body, instructions, direct.totalSize);
    }

    /** Emits the function compiled by {@code ctx} as split by {@code plan}. */
    static void emit(
            Plan plan,
            ClassVisitor classWriter,
            Context ctx,
            Context frameCtx,
            List<CompilerInstruction> instructions,
            InstructionAdapter asm) {
        if (plan.isTrampoline()) {
            emitTrampolineFunction(classWriter, plan, ctx, frameCtx, instructions, asm);
        } else {
            emitChunkedFunction(classWriter, plan.cuts, ctx, instructions, asm);
        }
    }

    // Direct-split chunks take every local as an argument.
    private static boolean fitsChunkArguments(FunctionType type, FunctionBody body) {
        int slots = 2; // Memory, Instance
        for (ValType param : type.params()) {
            slots += slotCount(param);
        }
        for (ValType local : body.localTypes()) {
            slots += slotCount(local);
        }
        return slots <= JVM_MAX_ARGUMENT_SLOTS;
    }

    // True if the JVM operand stack is verified (via ASM's own data-flow analysis,
    // not guessed) to be empty at the given label's position -- the precondition
    // for it to be safe as a chunk boundary, since chunk transitions don't spill
    // any pending stack values.
    private static boolean isStackEmptyAtLabel(
            long labelId,
            Map<Long, Label> labels,
            MethodNode methodNode,
            Frame<BasicValue>[] frames) {
        Label label = labels.get(labelId);
        if (label == null || !(label.info instanceof LabelNode)) {
            return false;
        }
        int nodePos = methodNode.instructions.indexOf((LabelNode) label.info);
        if (nodePos < 0 || nodePos >= frames.length || frames[nodePos] == null) {
            return false;
        }
        return frames[nodePos].getStackSize() == 0;
    }

    // The operand stack live at a label, as JVM types, or null if this label cannot
    // begin a chunk. A chunk boundary is a method boundary, so pending stack values
    // have to travel through the long[] frame -- which rules out references, and is
    // capped in depth. An empty array (stack already empty) is the easy case and
    // needs no spilling at all.
    private static Type[] spillableStackAtLabel(
            long labelId,
            Map<Long, Label> labels,
            MethodNode methodNode,
            Frame<BasicValue>[] frames) {
        Label label = labels.get(labelId);
        if (label == null || !(label.info instanceof LabelNode)) {
            return null;
        }
        int nodePos = methodNode.instructions.indexOf((LabelNode) label.info);
        if (nodePos < 0 || nodePos >= frames.length || frames[nodePos] == null) {
            return null;
        }
        Frame<BasicValue> frame = frames[nodePos];
        int depth = frame.getStackSize();
        if (depth > TRAMPOLINE_MAX_SPILL) {
            return null;
        }
        Type[] types = new Type[depth];
        for (int i = 0; i < depth; i++) {
            BasicValue value = frame.getStack(i);
            Type type = value == null ? null : value.getType();
            if (type == null) {
                return null;
            }
            switch (type.getSort()) {
                case Type.INT:
                case Type.LONG:
                case Type.FLOAT:
                case Type.DOUBLE:
                    types[i] = type;
                    break;
                default:
                    return null; // a reference cannot be stored in a long[]
            }
        }
        return types;
    }

    // Bit-preserving conversions between a stack value and its long[] slot. These
    // mirror what CompilerUtil does for locals, but work from the JVM types ASM's
    // analysis reports rather than from Wasm ValTypes.
    private static void emitStackValueToLong(InstructionAdapter asm, Type type) {
        switch (type.getSort()) {
            case Type.INT:
                asm.visitInsn(Opcodes.I2L);
                return;
            case Type.FLOAT:
                asm.invokestatic("java/lang/Float", "floatToRawIntBits", "(F)I", false);
                asm.visitInsn(Opcodes.I2L);
                return;
            case Type.DOUBLE:
                asm.invokestatic("java/lang/Double", "doubleToRawLongBits", "(D)J", false);
                return;
            default: // LONG needs no conversion
        }
    }

    private static void emitLongToStackValue(InstructionAdapter asm, Type type) {
        switch (type.getSort()) {
            case Type.INT:
                asm.visitInsn(Opcodes.L2I);
                return;
            case Type.FLOAT:
                asm.visitInsn(Opcodes.L2I);
                asm.invokestatic("java/lang/Float", "intBitsToFloat", "(I)F", false);
                return;
            case Type.DOUBLE:
                asm.invokestatic("java/lang/Double", "longBitsToDouble", "(J)D", false);
                return;
            default: // LONG needs no conversion
        }
    }

    // Which chunk a flattened-instruction-list position ends up in, given the final
    // list of cut points (each cuts[k] is the last instruction index of chunk k,
    // inclusive). Shared between the post-packing switch-alignment safety gate above
    // and the actual chunk-aware emission below, so both agree on the same mapping.
    private static int chunkIndexOf(int pos, List<Integer> cuts) {
        int idx = 0;
        for (int c : cuts) {
            if (pos > c) {
                idx++;
            } else {
                break;
            }
        }
        return idx;
    }

    private static int chunkIndexOf(int pos, int[] cuts) {
        int idx = 0;
        for (int c : cuts) {
            if (pos > c) {
                idx++;
            } else {
                break;
            }
        }
        return idx;
    }

    // Chooses chunk start positions for a trampoline split, or null if the function
    // can't be split this way. The governing rule is simple: a branch may leave its
    // chunk only by landing exactly on another chunk's first instruction, because
    // that is the only position the driver can dispatch to. Everything else here is
    // in service of finding a start set that satisfies it.
    private static Plan planTrampolineSplit(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> instructions,
            int totalSize) {

        Map<Long, Label> labels = new HashMap<>();
        for (var ins : instructions) {
            for (long target : ins.labelTargets()) {
                labels.putIfAbsent(target, new Label());
            }
        }
        Map<Long, Integer> labelDefPosition = new HashMap<>();
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (ins.opcode() == CompilerOpCode.LABEL && labels.containsKey(ins.operand(0))) {
                labelDefPosition.put(ins.operand(0), idx);
            }
        }

        // Same measurement approach as the linear planner: replay real emission into
        // a throwaway MethodNode so sizes are actual bytecode sizes and the operand
        // stack can be analyzed by ASM rather than guessed at.
        var methodNode =
                new MethodNode(
                        Opcodes.ASM9,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "measure",
                        methodTypeFor(type).toMethodDescriptorString(),
                        null,
                        null);
        var sizeCounter = new CodeSizeCounter(methodNode);
        var measureAsm = new InstructionAdapter(sizeCounter);
        Map<Integer, Integer> cumulativeSizeAtIndex = new HashMap<>();
        Set<Long> visitedTargets = new HashSet<>();
        emitLocalsDefaultInit(ctx, type, body, measureAsm);
        for (int idx = 0; idx < instructions.size(); idx++) {
            emitInstruction(ctx, labels, visitedTargets, instructions.get(idx), measureAsm);
            cumulativeSizeAtIndex.put(idx, sizeCounter.size);
        }
        methodNode.visitMaxs(512, ctx.trySaveBaseSlot() + 256);
        methodNode.visitEnd();

        Frame<BasicValue>[] frames;
        try {
            frames =
                    new Analyzer<>(new BasicInterpreter())
                            .analyze(ctx.internalClassName(), methodNode);
        } catch (AnalyzerException | RuntimeException e) {
            return null;
        }

        // A chunk boundary is a method boundary, so anything pending on the operand
        // stack has to travel through the frame. Labels where the stack is already
        // empty are free; the rest are still usable as long as their stack can be
        // spilled (no references, bounded depth), which is what lets a branch into
        // the middle of an expression still begin a chunk.
        TreeSet<Integer> candidateStarts = new TreeSet<>();
        candidateStarts.add(0);
        Map<Integer, Type[]> entryStacks = new HashMap<>();
        entryStacks.put(0, new Type[0]);
        for (Map.Entry<Long, Integer> e : labelDefPosition.entrySet()) {
            Type[] stack = spillableStackAtLabel(e.getKey(), labels, methodNode, frames);
            if (stack != null) {
                candidateStarts.add(e.getValue());
                entryStacks.put(e.getValue(), stack);
            }
        }

        // A JVM exception-table entry names a protected range and its handler, and
        // all of them must live in the method that declares it. So a try_table's
        // range, its handler, and the visitTryCatchBlock that declares it have to
        // end up in one chunk: no boundary may fall inside the region spanning all
        // four positions. Functions whose protected ranges blanket the body simply
        // run out of legal boundaries and fall back, which is the correct outcome.
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (ins.opcode() != CompilerOpCode.TRY_CATCH_BLOCK) {
                continue;
            }
            Integer rangeStart = labelDefPosition.get(ins.operand(0));
            Integer rangeEnd = labelDefPosition.get(ins.operand(1));
            Integer handler = labelDefPosition.get(ins.operand(2));
            if (rangeStart == null || rangeEnd == null || handler == null) {
                return null; // cannot place it safely, so do not split at all
            }
            int lo = Math.min(idx, Math.min(rangeStart, Math.min(rangeEnd, handler)));
            int hi = Math.max(idx, Math.max(rangeStart, Math.max(rangeEnd, handler)));
            // a start at p cuts before p, so p == lo is fine but lo < p <= hi is not
            candidateStarts.subSet(lo + 1, hi + 1).clear();
        }
        if (!candidateStarts.contains(0)) {
            candidateStarts.add(0); // chunk 0 always starts at the top
        }

        // every branch, as (source position, target position)
        List<int[]> edges = new ArrayList<>();
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            switch (ins.opcode()) {
                case GOTO:
                case IFEQ:
                case IFNE:
                case SWITCH:
                    for (long target : ins.labelTargets()) {
                        Integer defPos = labelDefPosition.get(target);
                        if (defPos == null) {
                            return null; // branch to a label we can't place -- don't risk it
                        }
                        edges.add(new int[] {idx, defPos});
                    }
                    break;
                default:
                    break;
            }
        }

        // Reserve for the prologue/epilogue while seeding. A chunk only syncs the
        // locals it actually touches (see emitTrampolineChunk), so reserving for
        // every local in the function -- as this used to -- shrinks the budget far
        // below what chunks really cost and splits functions more finely than
        // needed. Seed optimistically instead; the exact per-chunk cost is checked
        // afterwards, and a chunk that turns out too big is subdivided rather than
        // costing the whole function its split.
        int totalLocals = type.params().size() + body.localTypes().size();
        int budget =
                JIT_ELIGIBILITY_SIZE_BYTES
                        - (TRAMPOLINE_CHUNK_BASE_COST
                                + Math.min(totalLocals, 16) * TRAMPOLINE_LOCAL_SYNC_COST * 2);
        if (budget < 512) {
            return null; // so many locals that the sync alone would blow the budget
        }

        // A bail can depend on exactly where the boundaries landed: different cuts
        // make different branches cross chunks, and one of those may target a
        // position with a non-empty operand stack, which can never start a chunk.
        // So a failure at one budget is not proof the function is unsplittable --
        // retry with tighter budgets, which lands the cuts differently.
        for (int attempt = 0; attempt < 3; attempt++) {
            int attemptBudget = budget - (budget / 4) * attempt;
            if (attemptBudget < 512) {
                break;
            }
            int[] planned =
                    planTrampolineStarts(
                            attemptBudget,
                            candidateStarts,
                            edges,
                            cumulativeSizeAtIndex,
                            instructions,
                            totalLocals);
            if (planned != null) {
                Map<Integer, Type[]> chosen = new HashMap<>();
                for (int startPos : planned) {
                    chosen.put(startPos, entryStacks.getOrDefault(startPos, new Type[0]));
                }
                return new Plan(totalSize, null, planned, chosen);
            }
        }
        return null;
    }

    // Chooses chunk starts for one budget, or null if this budget cannot produce a
    // valid plan. Split out of planTrampolineSplit so a failed plan can simply be
    // retried at a different budget.
    private static int[] planTrampolineStarts(
            int budget,
            NavigableSet<Integer> candidateStarts,
            List<int[]> edges,
            Map<Integer, Integer> cumulativeSizeAtIndex,
            List<CompilerInstruction> instructions,
            int totalLocals) {
        // Seed with a purely size-driven partition, then repair it: any branch that
        // lands somewhere other than a chunk start forces that landing position to
        // become one. Adding starts only ever subdivides chunks, so this terminates
        // (worst case: every candidate becomes a start).
        TreeSet<Integer> starts = new TreeSet<>();
        starts.add(0);
        int chunkBase = 0; // cumulative size immediately before the current chunk
        int prev = -1; // last candidate that still fit inside the current chunk
        for (int c : candidateStarts) {
            if (c == 0) {
                continue;
            }
            if (cumulativeSizeAtIndex.get(c - 1) - chunkBase > budget) {
                // close the chunk at the last candidate that fit, not at the one
                // that overflowed
                if (prev > starts.last()) {
                    starts.add(prev);
                    chunkBase = cumulativeSizeAtIndex.get(prev - 1);
                }
                // the overflowing candidate may still not fit in the fresh chunk
                if (cumulativeSizeAtIndex.get(c - 1) - chunkBase > budget) {
                    starts.add(c);
                    chunkBase = cumulativeSizeAtIndex.get(c - 1);
                }
            }
            prev = c;
        }

        // Two invariants have to hold together, and fixing either can break the
        // other: every branch must land exactly on a chunk start, and every chunk
        // must fit the size limit once its real sync cost is counted. Subdividing an
        // oversized chunk can strand a branch that used to be internal to it, so the
        // branch repair is re-run after every subdivision rather than once up front.
        for (int round = 0; ; round++) {
            for (int pass = 0; ; pass++) {
                List<Integer> forced = new ArrayList<>();
                for (int[] edge : edges) {
                    int srcChunk = starts.floor(edge[0]);
                    int dstChunk = starts.floor(edge[1]);
                    if (srcChunk != dstChunk && dstChunk != edge[1]) {
                        forced.add(edge[1]);
                    }
                }
                if (forced.isEmpty()) {
                    break;
                }
                for (int f : forced) {
                    if (!candidateStarts.contains(f)) {
                        // A branch crosses into a position that can never begin a
                        // chunk (non-empty operand stack there). Not splittable.
                        return null;
                    }
                    starts.add(f);
                }
                if (starts.size() > TRAMPOLINE_MAX_CHUNKS || pass > 64) {
                    return null;
                }
            }

            // Now that the chunks are settled, cost each one exactly.
            List<Integer> startList = new ArrayList<>(starts);
            int oversized = -1;
            for (int k = 0; k < startList.size(); k++) {
                int from = startList.get(k);
                int to = (k + 1 < startList.size()) ? startList.get(k + 1) : instructions.size();
                int bodyBytes =
                        cumulativeSizeAtIndex.get(to - 1)
                                - (from == 0 ? 0 : cumulativeSizeAtIndex.get(from - 1));
                int cost =
                        bodyBytes
                                + trampolineChunkOverhead(
                                        instructions.subList(from, to), totalLocals);
                if (cost > JIT_ELIGIBILITY_SIZE_BYTES) {
                    oversized = k;
                    break;
                }
            }
            if (oversized < 0) {
                break;
            }
            if (round > 64 || starts.size() > TRAMPOLINE_MAX_CHUNKS) {
                return null;
            }

            // Subdivide the offending chunk at the LAST candidate that still fits,
            // so the leading piece is packed as full as the limit allows. Splitting
            // down the middle instead would leave both halves half-empty and end up
            // producing more, smaller chunks than necessary.
            int from = startList.get(oversized);
            int to =
                    (oversized + 1 < startList.size())
                            ? startList.get(oversized + 1)
                            : instructions.size();
            Integer split = null;
            int baseBytes = from == 0 ? 0 : cumulativeSizeAtIndex.get(from - 1);
            for (int c : candidateStarts.subSet(from + 1, to)) {
                int cost =
                        cumulativeSizeAtIndex.get(c - 1)
                                - baseBytes
                                + trampolineChunkOverhead(
                                        instructions.subList(from, c), totalLocals);
                if (cost > JIT_ELIGIBILITY_SIZE_BYTES) {
                    break;
                }
                split = c;
            }
            if (split == null) {
                // not even the first candidate fits; take it anyway so the loop
                // still makes progress, and let the next round subdivide further
                var tail = candidateStarts.subSet(from + 1, to);
                if (!tail.isEmpty()) {
                    split = tail.first();
                }
            }
            if (split == null) {
                // nothing inside it can legally begin a chunk
                return null;
            }
            starts.add(split);
        }

        if (starts.size() < 2 || starts.size() > TRAMPOLINE_MAX_CHUNKS) {
            return null;
        }

        List<Integer> startList = new ArrayList<>(starts);
        int[] result = new int[startList.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = startList.get(i);
        }
        return result;
    }

    private static Plan analyzeAndPlanSplit(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> instructions,
            boolean splitEligible) {

        // labels referenced by at least one branch/try-catch operand (matches exactly
        // what real emission marks -- see Compiler.emitInstruction).
        Map<Long, Label> labels = new HashMap<>();
        for (var ins : instructions) {
            for (long target : ins.labelTargets()) {
                labels.putIfAbsent(target, new Label());
            }
        }

        // where each such label is actually defined in the flat instruction stream.
        Map<Long, Integer> labelDefPosition = new HashMap<>();
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (ins.opcode() == CompilerOpCode.LABEL && labels.containsKey(ins.operand(0))) {
                labelDefPosition.put(ins.operand(0), idx);
            }
        }

        // loop headers (targets of a backward branch) and the [min,max] index span of
        // every real branch -- a cut point must not fall strictly inside any span.
        Set<Long> loopHeaders = new HashSet<>();
        List<int[]> spans = new ArrayList<>();
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            switch (ins.opcode()) {
                case GOTO:
                case IFEQ:
                case IFNE:
                case SWITCH:
                    for (long target : ins.labelTargets()) {
                        Integer defPos = labelDefPosition.get(target);
                        if (defPos == null) {
                            continue;
                        }
                        spans.add(new int[] {Math.min(idx, defPos), Math.max(idx, defPos)});
                        if (defPos <= idx) {
                            loopHeaders.add(target);
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        // buffer the exact same emission into a MethodNode while measuring cumulative
        // size, so we get both a precise byte count and a structure ASM can analyze.
        var methodNode =
                new MethodNode(
                        Opcodes.ASM9,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "measure",
                        methodTypeFor(type).toMethodDescriptorString(),
                        null,
                        null);
        var sizeCounter = new CodeSizeCounter(methodNode);
        var measureAsm = new InstructionAdapter(sizeCounter);

        Map<Integer, Integer> cumulativeSizeAtIndex = new HashMap<>();
        Set<Long> visitedTargets = new HashSet<>();
        emitLocalsDefaultInit(ctx, type, body, measureAsm);
        for (int idx = 0; idx < instructions.size(); idx++) {
            emitInstruction(ctx, labels, visitedTargets, instructions.get(idx), measureAsm);
            cumulativeSizeAtIndex.put(idx, sizeCounter.size);
        }
        int totalSize = sizeCounter.size;

        if (totalSize <= JIT_ELIGIBILITY_SIZE_BYTES || !splitEligible) {
            return new Plan(totalSize, null, null, null);
        }

        // Unlike ClassWriter's COMPUTE_MAXS, MethodNode never computes these on its
        // own -- Analyzer.computeInitialFrame sizes its frames directly off whatever
        // is here, so it must be set explicitly (and generously; this MethodNode is
        // discarded after analysis, so over-provisioning costs nothing).
        methodNode.visitMaxs(512, ctx.trySaveBaseSlot() + 256);
        methodNode.visitEnd();

        Frame<BasicValue>[] frames;
        try {
            frames =
                    new Analyzer<>(new BasicInterpreter())
                            .analyze(ctx.internalClassName(), methodNode);
        } catch (AnalyzerException | RuntimeException e) {
            return new Plan(totalSize, null, null, null);
        }

        // Positions listed here MUST become an actual cuts[] entry, not merely an
        // option the size-driven packer below may or may not take -- populated only
        // by the switch-eligibility loop further down. Everything else in
        // `candidates` remains purely opportunistic, exactly as before.
        Set<Integer> mandatoryCuts = new HashSet<>();

        List<Integer> candidates = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : labelDefPosition.entrySet()) {
            if (loopHeaders.contains(e.getKey())) {
                continue;
            }
            int idx = e.getValue();

            // A cut at idx puts instructions[0,idx] (inclusive of idx) in the earlier
            // chunk and instructions[idx+1,...) in the later one -- so a LABEL
            // instruction chosen as idx lands in the *earlier* chunk together with
            // whatever forward branch targets it, rather than being severed from it.
            // A span [s,e] is severed by that cut iff s <= idx < e.
            boolean insideSpan = false;
            for (int[] span : spans) {
                if (span[0] <= idx && idx < span[1]) {
                    insideSpan = true;
                    break;
                }
            }
            if (insideSpan) {
                continue;
            }

            if (!isStackEmptyAtLabel(e.getKey(), labels, methodNode, frames)) {
                continue;
            }

            candidates.add(idx);
        }

        // A br_table's targets all share one source position (the switch itself),
        // so the ordinary span check above pessimistically blocks almost every
        // candidate once a switch has more than a handful of targets scattered
        // through the function -- confirmed against real code: a 1,366-target
        // switch was the reason a 47KB BoxedWine function had zero viable
        // candidates. For switches at or above SWITCH_SPLIT_MIN_TARGETS, check
        // each distinct target independently against the same requirements as any
        // other candidate, exempting only this one switch's own spans -- never any
        // other branch's. Unlike a regular candidate, an eligible target is added
        // as a MANDATORY cut, not just an option for the packer: a far tableswitch
        // target is only reachable by calling straight into another chunk's start
        // (see emitChunkAwareSwitch), which requires that target's position to
        // actually BE a chunk boundary -- leaving it to chance, size-driven packing
        // would almost never land on more than one or two of potentially hundreds
        // of targets.
        //
        // Targets are judged individually rather than all-or-nothing: real
        // dispatch-loop-shaped code (the classic `for(;;) { switch(op) { ... } }`
        // interpreter-loop pattern -- confirmed in QuickJS's JS_CallInternal,
        // printf_core, resolve_labels, and BoxedWine's funcId 770) has most targets
        // sitting inside the enclosing loop's span, which can never be a cut point
        // no matter how eligibility is computed -- splitting a loop body across JVM
        // methods would need threading the loop's state through the call, which
        // this doesn't attempt. Requiring every target to qualify would throw away
        // the (sometimes substantial) minority of targets -- e.g. `break` cases
        // whose code sits after the loop's own back-edge -- that genuinely don't
        // have this problem. The post-packing safety gate below still independently
        // verifies every switch's actual outcome, so leaving some targets
        // ineligible here can only ever result in a safe bail, never wrong bytecode.
        for (int switchIdx = 0; switchIdx < instructions.size(); switchIdx++) {
            var ins = instructions.get(switchIdx);
            if (ins.opcode() != CompilerOpCode.SWITCH) {
                continue;
            }
            Set<Long> distinctTargets = new LinkedHashSet<>();
            ins.operands().forEach(distinctTargets::add);
            if (distinctTargets.size() < SWITCH_SPLIT_MIN_TARGETS) {
                continue;
            }

            for (long targetId : distinctTargets) {
                if (loopHeaders.contains(targetId)) {
                    continue;
                }
                Integer defPos = labelDefPosition.get(targetId);
                if (defPos == null) {
                    continue;
                }
                boolean blockedByOtherSpan = false;
                for (int[] span : spans) {
                    if (span[0] == switchIdx) {
                        continue; // this switch's own span -- deliberately exempted
                    }
                    if (span[0] <= defPos && defPos < span[1]) {
                        blockedByOtherSpan = true;
                        break;
                    }
                }
                if (blockedByOtherSpan
                        || !isStackEmptyAtLabel(targetId, labels, methodNode, frames)) {
                    continue;
                }
                candidates.add(defPos);
                mandatoryCuts.add(defPos);
            }
        }

        // Sorted, de-duplicated -- the same position can arrive twice (e.g. one
        // label targeted by two different eligible switches), and the packer below
        // needs a strictly increasing sequence to reason about "the cut right
        // before this one".
        TreeSet<Integer> sortedCandidates = new TreeSet<>(candidates);

        List<Integer> cuts = new ArrayList<>();
        int chunkStart = 0;
        int chunkStartSize = 0;
        int lastCandidate = -1;
        for (int idx : sortedCandidates) {
            int sizeAtIdx = cumulativeSizeAtIndex.get(idx);
            if (sizeAtIdx - chunkStartSize > JIT_ELIGIBILITY_SIZE_BYTES) {
                if (lastCandidate <= chunkStart) {
                    return new Plan(totalSize, null, null, null);
                }
                cuts.add(lastCandidate);
                chunkStart = lastCandidate;
                chunkStartSize = cumulativeSizeAtIndex.get(lastCandidate);
                if (sizeAtIdx - chunkStartSize > JIT_ELIGIBILITY_SIZE_BYTES) {
                    return new Plan(totalSize, null, null, null);
                }
            }
            if (mandatoryCuts.contains(idx)) {
                // A switch target must become an actual chunk boundary regardless
                // of whether it was strictly needed to stay under budget -- see
                // emitChunkAwareSwitch, which relies on every far target being
                // exactly reachable as another chunk's start.
                if (idx > chunkStart) {
                    cuts.add(idx);
                    chunkStart = idx;
                    chunkStartSize = sizeAtIdx;
                }
            } else {
                lastCandidate = idx;
            }
        }
        if (totalSize - chunkStartSize > JIT_ELIGIBILITY_SIZE_BYTES) {
            if (lastCandidate <= chunkStart) {
                return new Plan(totalSize, null, null, null);
            }
            cuts.add(lastCandidate);
            chunkStartSize = cumulativeSizeAtIndex.get(lastCandidate);
            if (totalSize - chunkStartSize > JIT_ELIGIBILITY_SIZE_BYTES) {
                return new Plan(totalSize, null, null, null);
            }
        }

        if (cuts.isEmpty()) {
            return new Plan(totalSize, null, null, null);
        }

        // Safety gate for switch splitting: a far switch target can only be reached
        // from another chunk by calling into that chunk's start, so it's only safe
        // to leave a target in a different chunk than its switch when that target's
        // position is EXACTLY where the greedy packer chose to start a chunk. The
        // packer above picks cuts purely by size, with no knowledge of switches, so
        // that alignment isn't guaranteed -- e.g. with 3 targets and only the first
        // chosen as a cut, the second could land mid-chunk, unreachable as a call
        // target. Rather than special-case the packer, verify the outcome here and
        // bail to no-split (same fallback as any other unsplittable function) if any
        // switch ends up with a target stranded away from a chunk boundary.
        for (int switchIdx = 0; switchIdx < instructions.size(); switchIdx++) {
            var ins = instructions.get(switchIdx);
            if (ins.opcode() != CompilerOpCode.SWITCH) {
                continue;
            }
            int switchChunk = chunkIndexOf(switchIdx, cuts);
            Set<Long> distinctTargets = new LinkedHashSet<>();
            ins.operands().forEach(distinctTargets::add);
            for (long targetId : distinctTargets) {
                Integer defPos = labelDefPosition.get(targetId);
                if (defPos == null) {
                    return new Plan(totalSize, null, null, null);
                }
                int targetChunk = chunkIndexOf(defPos, cuts);
                // A label's own position is the LAST instruction of the chunk that
                // contains it (chunks are [start, cuts[k]] inclusive), so the code
                // that actually follows the label -- what a jump to it must reach --
                // only starts at the very beginning of the NEXT chunk. That's only
                // reachable via a cross-chunk call when this label's position is
                // itself exactly a chosen cut, i.e. the last instruction of its chunk.
                if (targetChunk != switchChunk && !cuts.contains(defPos)) {
                    return new Plan(totalSize, null, null, null);
                }
            }
        }

        return new Plan(totalSize, cuts.stream().mapToInt(Integer::intValue).toArray(), null, null);
    }

    // Estimates JVM Code-attribute size by counting per-instruction encoding lengths
    // per the class file spec. Approximate (e.g. always assumes the wider constant-pool
    // index / offset form where an instruction has a narrow and wide encoding), so it
    // over-estimates. Standalone rather than asm-util's CodeSizeEvaluator because this
    // build's asm-util artifact has been minimized and no longer includes it.
    private static final class CodeSizeCounter extends MethodVisitor {
        int size;

        // Chains to a delegate (typically a MethodNode) so the same emission pass can
        // simultaneously measure size and build a buffered, analyzable method.
        CodeSizeCounter(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitInsn(int opcode) {
            size += 1;
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            size += (opcode == Opcodes.SIPUSH) ? 3 : 2;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            size += (var > 255) ? 4 : 2;
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            size += 3;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            size += 3;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            size += isInterface ? 5 : 3;
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String descriptor, Handle bootstrapMethod, Object... args) {
            size += 5;
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethod, args);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            size += 3;
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            size += (value instanceof Long || value instanceof Double) ? 3 : 2;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            size += (var > 255 || increment > 127 || increment < -128) ? 6 : 3;
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            // opcode + up to 3 padding bytes + default + low + high + N * 4-byte offsets
            size += 1 + 3 + 4 + 4 + 4 + (labels.length * 4);
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            // opcode + up to 3 padding bytes + default + npairs + N * (4-byte key + 4-byte offset)
            size += 1 + 3 + 4 + 4 + (labels.length * 8);
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            size += 4;
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }
    }

    // implements one compiled Wasm function's body: initializes locals, then

    // Emits an oversized function as several JVM methods, per the cut plan chosen by
    // analyzeAndPlanSplit above. Chunk 0 is emitted into the function's real, external
    // entry point (func_xxx) with its normal signature unchanged -- nothing outside
    // this one function's own emission needs to know it was split. Chunks 1..N-1 are
    // private helper methods (func_xxx_chunk_N), never called from anywhere else, that
    // additionally take Memory/Instance and every Wasm local as explicit parameters --
    // reusing Context's existing slot layout unmodified, since the extended signature
    // is built to match it exactly (params, Memory, Instance, then locals in order).
    // Chunks are chained with a plain call-then-return at each cut point; this only
    // ever happens once per static split point per invocation (never per loop
    // iteration -- the planner guarantees no cut sits inside a loop's own span), so it
    // adds a small, bounded number of extra calls rather than per-iteration overhead.
    private static void emitChunkedFunction(
            ClassVisitor classWriter,
            int[] cuts,
            Context ctx,
            List<CompilerInstruction> instructions,
            InstructionAdapter asm) {
        int funcId = ctx.getId();
        FunctionType type = ctx.getType();
        FunctionBody body = ctx.getBody();
        String internalClassName = ctx.internalClassName();

        // shared across all chunks: a jump never crosses a chunk boundary except at
        // the boundary itself, so one label/backward-jump-tracking state is correct
        // for the whole (conceptually single) function, split or not.
        Map<Long, Label> labels = new HashMap<>();
        for (var ins : instructions) {
            for (long target : ins.labelTargets()) {
                labels.putIfAbsent(target, new Label());
            }
        }
        // needed to resolve which chunk a far switch target lands in -- rebuilt here
        // rather than threaded through from analyzeAndPlanSplit's own copy, since
        // position indices are only meaningful relative to this same instructions list.
        Map<Long, Integer> labelDefPosition = new HashMap<>();
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (ins.opcode() == CompilerOpCode.LABEL && labels.containsKey(ins.operand(0))) {
                labelDefPosition.put(ins.operand(0), idx);
            }
        }
        Set<Long> visitedTargets = new HashSet<>();

        MethodType chunkType = chunkMethodType(type, body);
        String funcName = ctx.methodNameForFunc(funcId);
        int numChunks = cuts.length + 1;
        // internalClassName is a naming *prefix*, not itself the class chunk methods
        // actually live in -- they're emitted into the same FuncGroup class as this
        // function itself, exactly like an ordinary cross-function CALL resolves its
        // target class (see Emitters.CALL).
        String chunkClassName = ctx.classNameForFuncGroup(internalClassName, funcId);

        int start = 0;
        for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
            // cuts[chunkIdx] is the last instruction index included in this chunk
            // (inclusive), matching the span-safety check in analyzeAndPlanSplit.
            int end = (chunkIdx < cuts.length) ? cuts[chunkIdx] + 1 : instructions.size();
            List<CompilerInstruction> slice = instructions.subList(start, end);
            boolean isFirst = chunkIdx == 0;
            boolean isLast = chunkIdx == numChunks - 1;
            String nextChunkName = isLast ? null : funcName + "_chunk_" + (chunkIdx + 1);

            int thisChunkIdx = chunkIdx;
            // stub Labels are scoped per chunk: a far switch target gets one stub per
            // chunk that jumps to it (never shared across chunks, since a Label can't
            // be marked in more than one method), but a target referenced by several
            // switches within the SAME chunk shares one stub, tracked via stubBodyEmitted.
            Map<Long, Label> stubLabels = new HashMap<>();
            Set<Long> stubBodyEmitted = new HashSet<>();
            if (isFirst) {
                emitLocalsDefaultInit(ctx, type, body, asm);
                emitChunkAwareInstructions(
                        ctx,
                        type,
                        body,
                        slice,
                        labels,
                        labelDefPosition,
                        visitedTargets,
                        cuts,
                        thisChunkIdx,
                        chunkClassName,
                        funcName,
                        chunkType,
                        stubLabels,
                        stubBodyEmitted,
                        asm);
                if (!isLast) {
                    emitChunkCall(ctx, type, body, chunkClassName, nextChunkName, chunkType, asm);
                }
            } else {
                String chunkName = funcName + "_chunk_" + chunkIdx;
                emitFunction(
                        classWriter,
                        chunkName,
                        chunkType,
                        true,
                        chunkAsm -> {
                            emitChunkAwareInstructions(
                                    ctx,
                                    type,
                                    body,
                                    slice,
                                    labels,
                                    labelDefPosition,
                                    visitedTargets,
                                    cuts,
                                    thisChunkIdx,
                                    chunkClassName,
                                    funcName,
                                    chunkType,
                                    stubLabels,
                                    stubBodyEmitted,
                                    chunkAsm);
                            if (!isLast) {
                                emitChunkCall(
                                        ctx,
                                        type,
                                        body,
                                        chunkClassName,
                                        nextChunkName,
                                        chunkType,
                                        chunkAsm);
                            }
                        });
            }
            start = end;
        }
    }

    // Emits one chunk's slice of instructions. Every opcode goes through the plain,
    // unmodified emitInstruction EXCEPT a SWITCH with at least one target outside
    // this chunk -- analyzeAndPlanSplit's post-packing safety gate guarantees any
    // such target sits exactly at another chunk's start, so it's resolved to a small
    // stub that calls straight into that chunk instead of a local Label.
    private static void emitChunkAwareInstructions(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> slice,
            Map<Long, Label> labels,
            Map<Long, Integer> labelDefPosition,
            Set<Long> visitedTargets,
            int[] cuts,
            int currentChunkIdx,
            String chunkClassName,
            String funcName,
            MethodType chunkType,
            Map<Long, Label> stubLabels,
            Set<Long> stubBodyEmitted,
            InstructionAdapter asm) {
        for (var ins : slice) {
            if (ins.opcode() == CompilerOpCode.SWITCH
                    && hasFarTarget(ins, labelDefPosition, cuts, currentChunkIdx)) {
                emitChunkAwareSwitch(
                        ctx,
                        type,
                        body,
                        ins,
                        labels,
                        labelDefPosition,
                        cuts,
                        currentChunkIdx,
                        chunkClassName,
                        funcName,
                        chunkType,
                        stubLabels,
                        stubBodyEmitted,
                        asm);
            } else {
                emitInstruction(ctx, labels, visitedTargets, ins, asm);
            }
        }
    }

    private static boolean hasFarTarget(
            CompilerInstruction ins,
            Map<Long, Integer> labelDefPosition,
            int[] cuts,
            int currentChunkIdx) {
        return ins.operands()
                .anyMatch(
                        target ->
                                chunkIndexOf(labelDefPosition.get(target), cuts)
                                        != currentChunkIdx);
    }

    // A far-target SWITCH never has a backward (loop-header) target -- that's a
    // precondition of switch-split eligibility in analyzeAndPlanSplit, since a loop
    // header target disqualifies ALL of that switch's targets from the candidate
    // pool, so none of them could ever have driven a cut. That means the
    // CHECK_INTERRUPTION-before-backward-jump handling in the plain SWITCH case
    // (emitInstruction) never applies here and is safely omitted.
    private static void emitChunkAwareSwitch(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            CompilerInstruction ins,
            Map<Long, Label> labels,
            Map<Long, Integer> labelDefPosition,
            int[] cuts,
            int currentChunkIdx,
            String chunkClassName,
            String funcName,
            MethodType chunkType,
            Map<Long, Label> stubLabels,
            Set<Long> stubBodyEmitted,
            InstructionAdapter asm) {
        int n = ins.operandCount() - 1;
        Label[] table = new Label[n];
        for (int i = 0; i < n; i++) {
            table[i] =
                    resolveSwitchTarget(
                            ins.operand(i),
                            labels,
                            labelDefPosition,
                            cuts,
                            currentChunkIdx,
                            stubLabels);
        }
        Label defaultLabel =
                resolveSwitchTarget(
                        ins.operand(n),
                        labels,
                        labelDefPosition,
                        cuts,
                        currentChunkIdx,
                        stubLabels);
        asm.tableswitch(0, n - 1, defaultLabel, table);

        Set<Long> distinctTargets = new LinkedHashSet<>();
        ins.operands().forEach(distinctTargets::add);
        for (long targetId : distinctTargets) {
            int labelChunk = chunkIndexOf(labelDefPosition.get(targetId), cuts);
            if (labelChunk == currentChunkIdx || !stubBodyEmitted.add(targetId)) {
                continue;
            }
            // The label's own position is the LAST instruction of labelChunk (see
            // chunkIndexOf), so the code it actually names -- what the stub must
            // reach -- is the very start of the chunk right after it, not labelChunk
            // itself. The plan-time safety gate guarantees this alignment holds.
            int targetChunk = labelChunk + 1;
            asm.mark(stubLabels.get(targetId));
            emitChunkCall(
                    ctx,
                    type,
                    body,
                    chunkClassName,
                    funcName + "_chunk_" + targetChunk,
                    chunkType,
                    asm);
        }
    }

    // A local target resolves to its real, shared Label exactly as before. A far
    // target gets one stub Label per chunk (reused across every switch in this
    // chunk that shares the target); the stub body (a call into the target's own
    // chunk) is emitted once, right after the tableswitch, by the caller.
    private static Label resolveSwitchTarget(
            long targetId,
            Map<Long, Label> labels,
            Map<Long, Integer> labelDefPosition,
            int[] cuts,
            int currentChunkIdx,
            Map<Long, Label> stubLabels) {
        int targetChunk = chunkIndexOf(labelDefPosition.get(targetId), cuts);
        if (targetChunk == currentChunkIdx) {
            return labels.get(targetId);
        }
        return stubLabels.computeIfAbsent(targetId, id -> new Label());
    }

    // Emits a trampoline-split function: the real entry point keeps its normal
    // signature and becomes a driver loop over chunk methods, each of which
    // communicates "where to go next" through the frame's PC slot rather than by
    // jumping. See TRAMPOLINE_MAX_CHUNKS for why this exists.
    private static void emitTrampolineFunction(
            ClassVisitor classWriter,
            Plan plan,
            Context entryCtx,
            Context chunkCtx,
            List<CompilerInstruction> instructions,
            InstructionAdapter asm) {
        int funcId = entryCtx.getId();
        FunctionType type = entryCtx.getType();
        FunctionBody body = entryCtx.getBody();
        String internalClassName = entryCtx.internalClassName();
        int[] starts = plan.trampolineStarts;

        int totalLocals = type.params().size() + body.localTypes().size();
        int pcIndex = totalLocals; // the frame slot after all locals holds the PC
        // slots after the PC carry any operand-stack values a boundary has to spill
        Map<Integer, Type[]> entryStacks = plan.entryStacks;
        int maxSpill = 0;
        for (Type[] st : entryStacks.values()) {
            maxSpill = Math.max(maxSpill, st.length);
        }
        int spillBase = pcIndex + 1;
        int frameLength = spillBase + maxSpill;
        int numChunks = starts.length;
        String funcName = entryCtx.methodNameForFunc(funcId);
        String chunkClassName = entryCtx.classNameForFuncGroup(internalClassName, funcId);
        MethodType chunkType =
                methodType(jvmReturnType(type), long[].class, Memory.class, Instance.class);
        Type returnAsmType = getType(jvmReturnType(type));

        // ---- entry method: build the frame, then run the driver ----
        // Nothing but driver code is emitted into this method, so slots past the
        // ones Context reserved for the function's own body are free to use.
        int frameSlot = entryCtx.trySaveBaseSlot() + 8;
        asm.iconst(frameLength);
        asm.newarray(LONG_TYPE);
        asm.store(frameSlot, LONG_ARRAY_TYPE);

        // Params seed the frame; declared locals correctly default to 0, which the
        // freshly allocated array already provides.
        for (int i = 0; i < type.params().size(); i++) {
            var localType = localType(type, body, i);
            asm.load(frameSlot, LONG_ARRAY_TYPE);
            asm.iconst(i);
            asm.load(entryCtx.localSlotIndex(i), asmType(localType));
            emitJvmToLong(asm, localType);
            asm.astore(LONG_TYPE);
        }

        Label driverLoop = new Label();
        Label[] caseLabels = new Label[numChunks];
        for (int k = 0; k < numChunks; k++) {
            caseLabels[k] = new Label();
        }
        Label driverDefault = new Label();

        asm.mark(driverLoop);
        // One interruption check per dispatch. A Wasm loop whose back-edge became a
        // chunk transition no longer passes through the emitter's own backward-jump
        // check, so this is what preserves interruptibility for those loops.
        emitInvokeStatic(asm, CHECK_INTERRUPTION);
        asm.load(frameSlot, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.aload(LONG_TYPE);
        asm.visitInsn(Opcodes.L2I);
        asm.tableswitch(0, numChunks - 1, driverDefault, caseLabels);

        for (int k = 0; k < numChunks; k++) {
            asm.mark(caseLabels[k]);
            asm.load(frameSlot, LONG_ARRAY_TYPE);
            asm.load(entryCtx.memorySlot(), OBJECT_TYPE);
            asm.load(entryCtx.instanceSlot(), OBJECT_TYPE);
            asm.invokestatic(
                    chunkClassName,
                    trampolineChunkName(funcName, k),
                    chunkType.toMethodDescriptorString(),
                    false);
            // The chunk's return value is live on the stack here; whether it is the
            // function's real result depends entirely on the PC it left behind.
            asm.load(frameSlot, LONG_ARRAY_TYPE);
            asm.iconst(pcIndex);
            asm.aload(LONG_TYPE);
            asm.visitInsn(Opcodes.L2I);
            asm.iconst(TRAMPOLINE_PC_DONE);
            Label keepGoing = new Label();
            asm.visitJumpInsn(Opcodes.IF_ICMPNE, keepGoing);
            asm.areturn(returnAsmType);
            asm.mark(keepGoing);
            if (returnAsmType.getSize() == 2) {
                asm.pop2();
            } else if (returnAsmType.getSize() == 1) {
                asm.pop();
            }
            asm.goTo(driverLoop);
        }

        // Unreachable: the PC is only ever set by transitions this compiler emits.
        // Returning the default value keeps it well-formed without a throw.
        asm.mark(driverDefault);
        emitDefaultReturnValue(asm, returnAsmType);
        asm.areturn(returnAsmType);

        // ---- chunk methods ----
        Map<Long, Label> labels = new HashMap<>();
        for (var ins : instructions) {
            for (long target : ins.labelTargets()) {
                labels.putIfAbsent(target, new Label());
            }
        }
        Map<Long, Integer> labelDefPosition = new HashMap<>();
        for (int idx = 0; idx < instructions.size(); idx++) {
            var ins = instructions.get(idx);
            if (ins.opcode() == CompilerOpCode.LABEL && labels.containsKey(ins.operand(0))) {
                labelDefPosition.put(ins.operand(0), idx);
            }
        }
        Set<Long> visitedTargets = new HashSet<>();

        for (int k = 0; k < numChunks; k++) {
            int from = starts[k];
            int to = (k + 1 < numChunks) ? starts[k + 1] : instructions.size();
            List<CompilerInstruction> slice = instructions.subList(from, to);
            int chunkIdx = k;
            emitFunction(
                    classWriter,
                    trampolineChunkName(funcName, k),
                    chunkType,
                    true,
                    chunkAsm ->
                            emitTrampolineChunk(
                                    chunkCtx,
                                    type,
                                    body,
                                    slice,
                                    labels,
                                    labelDefPosition,
                                    visitedTargets,
                                    starts,
                                    chunkIdx,
                                    numChunks,
                                    pcIndex,
                                    spillBase,
                                    entryStacks,
                                    starts,
                                    totalLocals,
                                    returnAsmType,
                                    chunkAsm));
        }
    }

    // What one chunk's prologue and epilogue actually cost, from the locals it
    // really touches rather than the function's whole local count. Deliberately an
    // over-estimate of the emitted aload/iconst/laload/convert/store sequences, so
    // a plan that fits here fits for real.
    private static int trampolineChunkOverhead(List<CompilerInstruction> slice, int totalLocals) {
        boolean[] touched = new boolean[totalLocals];
        boolean[] written = new boolean[totalLocals];
        for (var ins : slice) {
            switch (ins.opcode()) {
                case LOCAL_GET:
                    touched[(int) ins.operand(0)] = true;
                    break;
                case LOCAL_SET:
                case LOCAL_TEE:
                    // written locals are loaded too -- see emitTrampolineChunk
                    touched[(int) ins.operand(0)] = true;
                    written[(int) ins.operand(0)] = true;
                    break;
                default:
                    break;
            }
        }
        int loads = 0;
        int stores = 0;
        for (int i = 0; i < totalLocals; i++) {
            if (touched[i]) {
                loads++;
            }
            if (written[i]) {
                stores++;
            }
        }
        return TRAMPOLINE_CHUNK_BASE_COST + (loads + stores) * TRAMPOLINE_LOCAL_SYNC_COST;
    }

    private static String trampolineChunkName(String funcName, int chunkIdx) {
        return funcName + "_tc_" + chunkIdx;
    }

    // Which chunk a position belongs to, given chunk *start* positions.
    private static int trampolineChunkOf(int pos, int[] starts) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= pos) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static void emitTrampolineChunk(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> slice,
            Map<Long, Label> labels,
            Map<Long, Integer> labelDefPosition,
            Set<Long> visitedTargets,
            int[] starts,
            int chunkIdx,
            int numChunks,
            int pcIndex,
            int spillBase,
            Map<Integer, Type[]> entryStacks,
            int[] startPositions,
            int totalLocals,
            Type returnAsmType,
            InstructionAdapter asm) {

        // Default the PC to DONE, so an ordinary `return` inside this slice needs no
        // special handling at all -- it just areturns, and the driver sees DONE.
        asm.load(0, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.lconst(TRAMPOLINE_PC_DONE);
        asm.astore(LONG_TYPE);

        // The frame is the single source of truth for locals; the JVM slots are
        // only this chunk's cache of it. So a chunk needs to load just the locals
        // it actually touches, and store back just the ones it writes -- a local
        // this chunk never mentions keeps its value in the frame untouched.
        //
        // Written locals must also be *loaded*, not just stored: a write that only
        // happens on one path through the chunk would otherwise leave the slot
        // undefined, and the shared epilogue stores unconditionally. Loading first
        // means the worst case is storing back the value that was already there.
        //
        // This matters most exactly where the trampoline is most valuable: a
        // dispatch-loop case body typically touches a handful of the function's
        // locals (JS_CallInternal has 48), and without this every one of them was
        // boxed and unboxed on every transition, per loop iteration.
        boolean[] readsLocal = new boolean[totalLocals];
        boolean[] writesLocal = new boolean[totalLocals];
        for (var ins : slice) {
            switch (ins.opcode()) {
                // an out-of-range index is a malformed module that would already
                // have failed validation, and would blow up in LOCAL_GET's own
                // emitter too -- so index directly rather than hiding it
                case LOCAL_GET:
                    readsLocal[(int) ins.operand(0)] = true;
                    break;
                case LOCAL_SET:
                case LOCAL_TEE:
                    // LOCAL_TEE dups the stack value and delegates to LOCAL_SET,
                    // so it writes the local without reading it
                    writesLocal[(int) ins.operand(0)] = true;
                    break;
                default:
                    break;
            }
        }

        for (int i = 0; i < totalLocals; i++) {
            if (!readsLocal[i] && !writesLocal[i]) {
                continue;
            }
            var localType = localType(type, body, i);
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(i);
            asm.aload(LONG_TYPE);
            emitLongToJvm(asm, localType);
            asm.store(ctx.localSlotIndex(i), asmType(localType));
        }

        // Reload whatever operand-stack values this chunk was entered with. They
        // were spilled by whichever transition jumped here, and must be pushed back
        // in the same bottom-to-top order before the first instruction runs.
        Type[] entryStack = entryStacks.getOrDefault(startPositions[chunkIdx], EMPTY_TYPES);
        for (int i = 0; i < entryStack.length; i++) {
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(spillBase + i);
            asm.aload(LONG_TYPE);
            emitLongToStackValue(asm, entryStack[i]);
        }

        // Every cross-chunk exit funnels through one shared epilogue, so the cost of
        // boxing all the locals back is paid once per chunk rather than once per
        // transition -- which matters a lot for a dispatch chunk with hundreds of
        // outgoing switch targets.
        Label sharedExit = new Label();
        int pcTempSlot = ctx.trySaveBaseSlot() + 8;
        Map<Integer, Label> switchStubs = new HashMap<>();

        for (var ins : slice) {
            emitTrampolineInstruction(
                    ctx,
                    labels,
                    labelDefPosition,
                    visitedTargets,
                    starts,
                    chunkIdx,
                    sharedExit,
                    pcTempSlot,
                    switchStubs,
                    entryStacks,
                    spillBase,
                    ins,
                    asm);
        }

        // Falling off the end of a chunk continues into the next one. For the last
        // chunk this is unreachable (the body ends in a return), but emitting a
        // well-formed DONE keeps the method verifiable either way.
        emitTrampolineTransition(
                ctx,
                chunkIdx + 1 < numChunks ? chunkIdx + 1 : TRAMPOLINE_PC_DONE,
                startPositions,
                entryStacks,
                spillBase,
                pcTempSlot,
                sharedExit,
                asm);

        for (Map.Entry<Integer, Label> stub : switchStubs.entrySet()) {
            asm.mark(stub.getValue());
            emitTrampolineTransition(
                    ctx,
                    stub.getKey(),
                    startPositions,
                    entryStacks,
                    spillBase,
                    pcTempSlot,
                    sharedExit,
                    asm);
        }

        asm.mark(sharedExit);
        for (int i = 0; i < totalLocals; i++) {
            if (!writesLocal[i]) {
                continue; // this chunk never changed it, so the frame is still right
            }
            var localType = localType(type, body, i);
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(i);
            asm.load(ctx.localSlotIndex(i), asmType(localType));
            emitJvmToLong(asm, localType);
            asm.astore(LONG_TYPE);
        }
        asm.load(0, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.load(pcTempSlot, INT_TYPE);
        asm.visitInsn(Opcodes.I2L);
        asm.astore(LONG_TYPE);
        emitDefaultReturnValue(asm, returnAsmType);
        asm.areturn(returnAsmType);
    }

    // Like emitInstruction, but any branch leaving this chunk becomes a transition
    // through the shared epilogue instead of a jump. Non-branching opcodes are
    // emitted by the ordinary path, unchanged.
    private static void emitTrampolineInstruction(
            Context ctx,
            Map<Long, Label> labels,
            Map<Long, Integer> labelDefPosition,
            Set<Long> visitedTargets,
            int[] starts,
            int chunkIdx,
            Label sharedExit,
            int pcTempSlot,
            Map<Integer, Label> switchStubs,
            Map<Integer, Type[]> entryStacks,
            int spillBase,
            CompilerInstruction ins,
            InstructionAdapter asm) {

        switch (ins.opcode()) {
            case GOTO:
                {
                    int target = trampolineTargetChunk(ins.operand(0), labelDefPosition, starts);
                    if (target == chunkIdx) {
                        break; // handled by the ordinary emitter below
                    }
                    emitTrampolineTransition(
                            ctx,
                            target,
                            starts,
                            entryStacks,
                            spillBase,
                            pcTempSlot,
                            sharedExit,
                            asm);
                    return;
                }
            case IFEQ:
                {
                    int target = trampolineTargetChunk(ins.operand(0), labelDefPosition, starts);
                    if (target == chunkIdx) {
                        break;
                    }
                    Label notTaken = new Label();
                    asm.ifne(notTaken);
                    emitTrampolineTransition(
                            ctx,
                            target,
                            starts,
                            entryStacks,
                            spillBase,
                            pcTempSlot,
                            sharedExit,
                            asm);
                    asm.mark(notTaken);
                    return;
                }
            case IFNE:
                {
                    int target = trampolineTargetChunk(ins.operand(0), labelDefPosition, starts);
                    if (target == chunkIdx) {
                        break;
                    }
                    Label notTaken = new Label();
                    asm.ifeq(notTaken);
                    emitTrampolineTransition(
                            ctx,
                            target,
                            starts,
                            entryStacks,
                            spillBase,
                            pcTempSlot,
                            sharedExit,
                            asm);
                    asm.mark(notTaken);
                    return;
                }
            case SWITCH:
                {
                    boolean anyFar =
                            ins.operands()
                                    .anyMatch(
                                            t ->
                                                    trampolineTargetChunk(
                                                                    t, labelDefPosition, starts)
                                                            != chunkIdx);
                    if (!anyFar) {
                        break;
                    }
                    int n = ins.operandCount() - 1;
                    Label[] table = new Label[n];
                    for (int i = 0; i < n; i++) {
                        table[i] =
                                trampolineSwitchLabel(
                                        ins.operand(i),
                                        labels,
                                        labelDefPosition,
                                        starts,
                                        chunkIdx,
                                        switchStubs);
                    }
                    Label defaultLabel =
                            trampolineSwitchLabel(
                                    ins.operand(n),
                                    labels,
                                    labelDefPosition,
                                    starts,
                                    chunkIdx,
                                    switchStubs);
                    asm.tableswitch(0, n - 1, defaultLabel, table);
                    return;
                }
            default:
                break;
        }
        emitInstruction(ctx, labels, visitedTargets, ins, asm);
    }

    private static int trampolineTargetChunk(
            long labelId, Map<Long, Integer> labelDefPosition, int[] starts) {
        Integer defPos = labelDefPosition.get(labelId);
        if (defPos == null) {
            return -1;
        }
        return trampolineChunkOf(defPos, starts);
    }

    private static Label trampolineSwitchLabel(
            long labelId,
            Map<Long, Label> labels,
            Map<Long, Integer> labelDefPosition,
            int[] starts,
            int chunkIdx,
            Map<Integer, Label> switchStubs) {
        int target = trampolineTargetChunk(labelId, labelDefPosition, starts);
        if (target == chunkIdx) {
            return labels.get(labelId);
        }
        // One stub per destination chunk, shared by every switch entry heading there.
        return switchStubs.computeIfAbsent(target, k -> new Label());
    }

    // Leaves for another chunk. Any operand-stack values pending here belong to the
    // destination (the verifier guarantees the stack at a branch matches its target),
    // so they are spilled into the frame and the stack left empty -- which is also
    // what lets every transition share one epilogue, since they all arrive there
    // with the same empty stack.
    private static void emitTrampolineTransition(
            Context ctx,
            int targetChunk,
            int[] startPositions,
            Map<Integer, Type[]> entryStacks,
            int spillBase,
            int pcTempSlot,
            Label sharedExit,
            InstructionAdapter asm) {
        Type[] stack =
                (targetChunk >= 0 && targetChunk < startPositions.length)
                        ? entryStacks.getOrDefault(startPositions[targetChunk], EMPTY_TYPES)
                        : EMPTY_TYPES;
        // pop top-down into scratch slots, then store bottom-up into the frame
        int tempBase = ctx.trySaveBaseSlot() + 16;
        for (int i = stack.length - 1; i >= 0; i--) {
            emitStackValueToLong(asm, stack[i]);
            asm.store(tempBase + i * 2, LONG_TYPE);
        }
        for (int i = 0; i < stack.length; i++) {
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(spillBase + i);
            asm.load(tempBase + i * 2, LONG_TYPE);
            asm.astore(LONG_TYPE);
        }
        asm.iconst(targetChunk);
        asm.store(pcTempSlot, INT_TYPE);
        asm.goTo(sharedExit);
    }

    private static final Type[] EMPTY_TYPES = new Type[0];

    private static void emitDefaultReturnValue(InstructionAdapter asm, Type returnAsmType) {
        switch (returnAsmType.getSort()) {
            case Type.VOID:
                return;
            case Type.LONG:
                asm.lconst(0L);
                return;
            case Type.FLOAT:
                asm.fconst(0f);
                return;
            case Type.DOUBLE:
                asm.dconst(0d);
                return;
            case Type.OBJECT:
            case Type.ARRAY:
                asm.aconst(null);
                return;
            default:
                asm.iconst(0);
        }
    }

    // The JVM method type shared by every chunk after the first: the function's own
    // params, then Memory and Instance (matching Context's memorySlot/instanceSlot),
    // then every declared local in order (matching Context's local slot layout for
    // indices >= params.size()). Chunk 0 keeps the function's normal, unextended
    // signature -- it's the real entry point and receives locals via initialization,
    // not as arguments.
    private static MethodType chunkMethodType(FunctionType type, FunctionBody body) {
        var params = new ArrayList<Class<?>>();
        params.addAll(List.of(jvmParameterTypes(type)));
        params.add(Memory.class);
        params.add(Instance.class);
        for (ValType local : body.localTypes()) {
            params.add(jvmType(local));
        }
        return methodType(jvmReturnType(type), params.toArray(new Class<?>[0]));
    }

    // Loads every param and local from this chunk's own JVM slots (via the shared
    // Context, so slot numbers line up identically in every chunk), calls the next
    // chunk, and returns its result directly.
    private static void emitChunkCall(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            String internalClassName,
            String nextChunkName,
            MethodType chunkType,
            InstructionAdapter asm) {
        // Argument order must match chunkMethodType exactly: params, then Memory,
        // then Instance, then declared locals.
        int paramCount = type.params().size();
        int totalLocals = paramCount + body.localTypes().size();
        for (int i = 0; i < paramCount; i++) {
            var localType = localType(type, body, i);
            asm.load(ctx.localSlotIndex(i), asmType(localType));
        }
        asm.load(ctx.memorySlot(), OBJECT_TYPE);
        asm.load(ctx.instanceSlot(), OBJECT_TYPE);
        for (int i = paramCount; i < totalLocals; i++) {
            var localType = localType(type, body, i);
            asm.load(ctx.localSlotIndex(i), asmType(localType));
        }
        asm.invokestatic(
                internalClassName, nextChunkName, chunkType.toMethodDescriptorString(), false);
        asm.areturn(getType(jvmReturnType(type)));
    }
}
