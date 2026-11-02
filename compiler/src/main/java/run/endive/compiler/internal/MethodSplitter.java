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

    // Headroom under the 65535-byte Code attribute limit, since sizes are estimates.
    private static final int JVM_METHOD_SIZE_CEILING = 48000;

    // HotSpot's HugeMethodLimit, as for dispatch methods (endive.hugeMethodLimit).
    private static final int JIT_ELIGIBILITY_SIZE_BYTES =
            Math.min(Compiler.HUGE_METHOD_LIMIT, JVM_METHOD_SIZE_CEILING);

    // br_tables with at least this many targets may cut at each target (direct split).
    private static final int SWITCH_SPLIT_MIN_TARGETS = 8;

    // Caps the trampoline chunk methods emitted for one function.
    private static final int TRAMPOLINE_MAX_CHUNKS = 4096;

    // PC value meaning the chunk returned the function's result.
    private static final int TRAMPOLINE_PC_DONE = -1;

    // Per-chunk bytes for the PC stores and the dummy return (over-estimate).
    private static final int TRAMPOLINE_CHUNK_BASE_COST = 64;

    // Bytes to sync one local in one direction (over-estimate).
    private static final int TRAMPOLINE_LOCAL_SYNC_COST = 10;

    // Most operand-stack values a chunk boundary may carry.
    private static final int TRAMPOLINE_MAX_SPILL = 8;

    // The JVM limit on argument slots of a static method.
    private static final int JVM_MAX_ARGUMENT_SLOTS = 255;

    /** Returns how to split the function compiled by {@code ctx}, or null to keep it whole. */
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
        // try_table is only supported by the trampoline.
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

    // True if ASM's analysis shows an empty operand stack at the label.
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

    // Stack types at a label if they can be spilled into the frame, else null.
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

    // Bit-preserving conversions between a stack value and its long[] slot.
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

    // Chunk containing pos, where cuts[k] is the last index of chunk k.
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

    // Chunk starts for a trampoline split, or null.
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

        // Emit for real to measure sizes and analyze the operand stack.
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

        // Labels whose stack can be spilled into the frame may start a chunk.
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

        // A try_table's range, handler and declaration must stay in one chunk.
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

        // Optimistic reserve: chunks are costed exactly in planTrampolineStarts.
        int totalLocals = type.params().size() + body.localTypes().size();
        int budget =
                JIT_ELIGIBILITY_SIZE_BYTES
                        - (TRAMPOLINE_CHUNK_BASE_COST
                                + Math.min(totalLocals, 16) * TRAMPOLINE_LOCAL_SYNC_COST * 2);
        if (budget < 512) {
            return null; // so many locals that the sync alone would blow the budget
        }

        // Different budgets place the cuts differently, so retry before giving up.
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

    // Chunk starts for one budget, or null.
    private static int[] planTrampolineStarts(
            int budget,
            NavigableSet<Integer> candidateStarts,
            List<int[]> edges,
            Map<Integer, Integer> cumulativeSizeAtIndex,
            List<CompilerInstruction> instructions,
            int totalLocals) {
        // Seed by size, then make every cross-chunk branch target a start.
        TreeSet<Integer> starts = new TreeSet<>();
        starts.add(0);
        int chunkBase = 0; // cumulative size immediately before the current chunk
        int prev = -1; // last candidate that still fit inside the current chunk
        for (int c : candidateStarts) {
            if (c == 0) {
                continue;
            }
            if (cumulativeSizeAtIndex.get(c - 1) - chunkBase > budget) {
                // close the chunk at the last candidate that fit
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

        // Repeat until every cross-chunk branch lands on a start and every chunk fits.
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
                        // the target cannot start a chunk
                        return null;
                    }
                    starts.add(f);
                }
                if (starts.size() > TRAMPOLINE_MAX_CHUNKS || pass > 64) {
                    return null;
                }
            }

            // cost each chunk exactly
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

            // Split at the last candidate that fits, packing the leading piece full.
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
                // nothing fits: take the first candidate and subdivide again next round
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

        // labels targeted by a branch or try-catch, as in Compiler.emitInstruction
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

        // loop headers, and the span of every branch: no cut may fall inside a span
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

        // Emit for real to measure sizes and analyze the operand stack.
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

        // MethodNode does not compute these, and the Analyzer needs them.
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

        // switch targets that must become cuts
        Set<Integer> mandatoryCuts = new HashSet<>();

        List<Integer> candidates = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : labelDefPosition.entrySet()) {
            if (loopHeaders.contains(e.getKey())) {
                continue;
            }
            int idx = e.getValue();

            // a cut at idx keeps instructions[0, idx] in the earlier chunk
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

        // Large br_table: each safe target becomes a mandatory cut, ignoring this switch's spans.
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
                // far switch targets must start a chunk, see emitChunkAwareSwitch
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

        // Every switch target outside its switch's chunk must start a chunk.
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
                // the code after a label starts the next chunk, so the label must be a cut
                if (targetChunk != switchChunk && !cuts.contains(defPos)) {
                    return new Plan(totalSize, null, null, null);
                }
            }
        }

        return new Plan(totalSize, cuts.stream().mapToInt(Integer::intValue).toArray(), null, null);
    }

    // Over-estimates bytecode size; asm-util's CodeSizeEvaluator is not in this build.
    private static final class CodeSizeCounter extends MethodVisitor {
        int size;

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

    // Emits a direct split: chunk 0 in the function's method, chunk k in <name>_chunk_<k>.
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

        // one label state for all chunks, since no jump crosses a cut
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

        MethodType chunkType = chunkMethodType(type, body);
        String funcName = ctx.methodNameForFunc(funcId);
        int numChunks = cuts.length + 1;
        // chunk methods live in the function's FuncGroup class
        String chunkClassName = ctx.classNameForFuncGroup(internalClassName, funcId);

        int start = 0;
        for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
            int end = (chunkIdx < cuts.length) ? cuts[chunkIdx] + 1 : instructions.size();
            List<CompilerInstruction> slice = instructions.subList(start, end);
            boolean isFirst = chunkIdx == 0;
            boolean isLast = chunkIdx == numChunks - 1;
            String nextChunkName = isLast ? null : funcName + "_chunk_" + (chunkIdx + 1);

            int thisChunkIdx = chunkIdx;
            // stubs are per chunk, since a Label belongs to one method
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

    // Emits one chunk; a switch with far targets goes through emitChunkAwareSwitch.
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

    // Far switch targets are never loop headers, so no interruption check is needed.
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
            // the code after the label starts the next chunk
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

    // Local targets keep their Label; far targets share a per-chunk stub.
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

    // Emits a trampoline split: a driver loop in the function's method, chunk k in <name>_tc_<k>.
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
        // spill slots follow the PC slot
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

        // entry method: build the frame and run the driver; slots past the body's are free
        int frameSlot = entryCtx.trySaveBaseSlot() + 8;
        asm.iconst(frameLength);
        asm.newarray(LONG_TYPE);
        asm.store(frameSlot, LONG_ARRAY_TYPE);

        // params seed the frame; declared locals are already 0
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
        // covers loop back-edges that became chunk transitions
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
            // the result counts only if the chunk left the PC at DONE
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

        // unreachable: the PC is only set by emitted transitions
        asm.mark(driverDefault);
        emitDefaultReturnValue(asm, returnAsmType);
        asm.areturn(returnAsmType);

        // chunk methods
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

    // Prologue and epilogue cost from the locals the chunk touches (over-estimate).
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

        // a plain return leaves the PC at DONE, so the driver returns the value
        asm.load(0, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.lconst(TRAMPOLINE_PC_DONE);
        asm.astore(LONG_TYPE);

        // load touched locals, written ones too since a write may be on one path only
        boolean[] readsLocal = new boolean[totalLocals];
        boolean[] writesLocal = new boolean[totalLocals];
        for (var ins : slice) {
            switch (ins.opcode()) {
                case LOCAL_GET:
                    readsLocal[(int) ins.operand(0)] = true;
                    break;
                case LOCAL_SET:
                case LOCAL_TEE:
                    // LOCAL_TEE writes the local without reading it
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

        // reload the spilled stack, bottom to top
        Type[] entryStack = entryStacks.getOrDefault(startPositions[chunkIdx], EMPTY_TYPES);
        for (int i = 0; i < entryStack.length; i++) {
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(spillBase + i);
            asm.aload(LONG_TYPE);
            emitLongToStackValue(asm, entryStack[i]);
        }

        // all cross-chunk exits share one epilogue
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

        // falling off the end continues into the next chunk
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

    // Like emitInstruction, but branches leaving the chunk become transitions.
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

    // Spills the stack for the target chunk, sets the PC and jumps to the epilogue.
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

    // Direct-split chunks after the first take params, Memory, Instance, then locals.
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

    // Calls the next direct-split chunk with all params and locals and returns its result.
    private static void emitChunkCall(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            String internalClassName,
            String nextChunkName,
            MethodType chunkType,
            InstructionAdapter asm) {
        // order must match chunkMethodType
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
