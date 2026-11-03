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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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

    /** How to split one function. */
    static final class Plan {
        final int totalSize;
        final boolean trampoline;
        // index of the first instruction of each chunk
        final int[] starts;
        // trampoline only: operand-stack types each chunk receives through the frame
        final Type[][] entryStacks;

        private Plan(int totalSize, boolean trampoline, int[] starts, Type[][] entryStacks) {
            this.totalSize = totalSize;
            this.trampoline = trampoline;
            this.starts = starts;
            this.entryStacks = entryStacks;
        }

        boolean isTrampoline() {
            return trampoline;
        }

        int chunkCount() {
            return starts.length;
        }
    }

    private static final Type LONG_ARRAY_TYPE = Type.getType(long[].class);
    private static final Type[] EMPTY_STACK = new Type[0];

    private static final int MIN_INSTRUCTIONS = 200;
    // Sizes are estimates, so stay well under the JVM's 64 KB even with a raised limit.
    private static final int MAX_CHUNK_SIZE = Math.min(Compiler.HUGE_METHOD_LIMIT, 48000);
    private static final int SWITCH_CUT_MIN_TARGETS = 8;
    private static final int MAX_ARGUMENT_SLOTS = 255;
    private static final int MAX_TRAMPOLINE_CHUNKS = 4096;
    private static final int MAX_SPILL = 8;
    private static final int CHUNK_BASE_COST = 64;
    private static final int LOCAL_SYNC_COST = 10;
    private static final int PC_DONE = -1;

    private final Context ctx;
    private final FunctionType type;
    private final FunctionBody body;
    private final List<CompilerInstruction> instructions;
    private final int totalLocals;
    // trampoline frame: locals, then the PC, then the spilled operand stack
    private final int pcIndex;
    private final int spillBase;
    private final Type returnType;
    private final String funcName;
    private final String chunkClass;
    private final Map<Long, Label> labels = new HashMap<>();
    private final Map<Long, Integer> labelPositions = new HashMap<>();
    private final Set<Long> visitedTargets = new HashSet<>();

    // set while planning
    private int[] sizes;
    private final Map<Long, Frame<BasicValue>> labelFrames = new HashMap<>();

    private MethodSplitter(Context ctx, List<CompilerInstruction> instructions) {
        this.ctx = ctx;
        this.type = ctx.getType();
        this.body = ctx.getBody();
        this.instructions = instructions;
        this.totalLocals = type.params().size() + body.localTypes().size();
        this.pcIndex = totalLocals;
        this.spillBase = totalLocals + 1;
        this.returnType = getType(jvmReturnType(type));
        this.funcName = ctx.methodNameForFunc(ctx.getId());
        this.chunkClass = ctx.classNameForFuncGroup(ctx.internalClassName(), ctx.getId());
        for (var ins : instructions) {
            for (long target : ins.labelTargets()) {
                labels.putIfAbsent(target, new Label());
            }
        }
        for (int i = 0; i < instructions.size(); i++) {
            var ins = instructions.get(i);
            if (ins.opcode() == CompilerOpCode.LABEL && labels.containsKey(ins.operand(0))) {
                labelPositions.put(ins.operand(0), i);
            }
        }
    }

    /** Returns how to split the function compiled by {@code ctx}, or null to keep it whole. */
    static Plan plan(Context ctx, List<CompilerInstruction> instructions) {
        var type = ctx.getType();
        if (instructions.size() <= MIN_INSTRUCTIONS
                || hasTooManyParameters(type)
                || type.hasObjectRefParams()
                || type.hasObjectRefReturns()
                || ctx.getBody().localTypes().stream().anyMatch(ValType::isObjectRef)) {
            return null;
        }
        var splitter = new MethodSplitter(ctx, instructions);
        var method = splitter.measure();
        int totalSize = splitter.sizes[instructions.size() - 1];
        if (totalSize <= MAX_CHUNK_SIZE || !splitter.analyze(method)) {
            return null;
        }
        var branches = splitter.branches();
        if (branches == null) {
            return null;
        }
        int[] starts = splitter.canSplitDirect() ? splitter.planDirect(branches) : null;
        if (starts != null) {
            return new Plan(totalSize, false, starts, null);
        }
        return splitter.planTrampoline(totalSize, branches);
    }

    /** Emits the function compiled by {@code ctx} as split by {@code plan}. */
    static void emit(
            Plan plan,
            ClassVisitor classWriter,
            Context ctx,
            Context frameCtx,
            List<CompilerInstruction> instructions,
            InstructionAdapter asm) {
        if (plan.trampoline) {
            new MethodSplitter(frameCtx, instructions).emitTrampoline(plan, classWriter, ctx, asm);
        } else {
            new MethodSplitter(ctx, instructions).emitDirect(plan.starts, classWriter, asm);
        }
    }

    // Emits the function once, recording the bytecode size after each instruction.
    private MethodNode measure() {
        var method =
                new MethodNode(
                        Opcodes.ASM9,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "measure",
                        methodTypeFor(type).toMethodDescriptorString(),
                        null,
                        null);
        var counter = new CodeSizeCounter(method);
        var asm = new InstructionAdapter(counter);
        sizes = new int[instructions.size()];
        emitLocalsDefaultInit(ctx, type, body, asm);
        for (int i = 0; i < instructions.size(); i++) {
            emitInstruction(ctx, labels, visitedTargets, instructions.get(i), asm);
            sizes[i] = counter.size;
        }
        return method;
    }

    // Records the operand stack at each label.
    private boolean analyze(MethodNode method) {
        // the Analyzer needs bounds; a function exceeding them is not split
        method.visitMaxs(512, ctx.trySaveBaseSlot() + 256);
        method.visitEnd();
        Frame<BasicValue>[] frames;
        try {
            frames =
                    new Analyzer<>(new BasicInterpreter()).analyze(ctx.internalClassName(), method);
        } catch (AnalyzerException | RuntimeException e) {
            return false;
        }
        for (var label : labels.entrySet()) {
            if (label.getValue().info instanceof LabelNode) {
                int pos = method.instructions.indexOf((LabelNode) label.getValue().info);
                if (pos >= 0 && pos < frames.length && frames[pos] != null) {
                    labelFrames.put(label.getKey(), frames[pos]);
                }
            }
        }
        return true;
    }

    // Every branch as {source index, target index}, or null if a target is not placed.
    private List<int[]> branches() {
        List<int[]> branches = new ArrayList<>();
        for (int i = 0; i < instructions.size(); i++) {
            var ins = instructions.get(i);
            switch (ins.opcode()) {
                case GOTO:
                case IFEQ:
                case IFNE:
                case SWITCH:
                    for (long target : ins.labelTargets()) {
                        Integer pos = labelPositions.get(target);
                        if (pos == null) {
                            return null;
                        }
                        branches.add(new int[] {i, pos});
                    }
                    break;
                default:
                    break;
            }
        }
        return branches;
    }

    private boolean canSplitDirect() {
        int slots = 2; // Memory, Instance
        for (ValType param : type.params()) {
            slots += slotCount(param);
        }
        for (ValType local : body.localTypes()) {
            slots += slotCount(local);
        }
        return slots <= MAX_ARGUMENT_SLOTS
                && instructions.stream()
                        .noneMatch(ins -> ins.opcode() == CompilerOpCode.TRY_CATCH_BLOCK);
    }

    // Chunk starts for a direct split, or null. A cut after a label ends a chunk with it.
    private int[] planDirect(List<int[]> branches) {
        Set<Integer> loopHeaders = new HashSet<>();
        for (int[] branch : branches) {
            if (branch[1] <= branch[0]) {
                loopHeaders.add(branch[1]);
            }
        }

        TreeSet<Integer> candidates = new TreeSet<>();
        for (var label : labelPositions.entrySet()) {
            int pos = label.getValue();
            if (!loopHeaders.contains(pos)
                    && !isCrossed(branches, pos, -1)
                    && isStackEmpty(label.getKey())) {
                candidates.add(pos);
            }
        }

        // Each target of a large br_table that can be cut must be, ignoring its own branches.
        Set<Integer> mandatory = new HashSet<>();
        for (int i = 0; i < instructions.size(); i++) {
            var ins = instructions.get(i);
            if (ins.opcode() != CompilerOpCode.SWITCH
                    || ins.operands().distinct().count() < SWITCH_CUT_MIN_TARGETS) {
                continue;
            }
            for (long target : ins.operands().distinct().toArray()) {
                Integer pos = labelPositions.get(target);
                if (pos == null
                        || loopHeaders.contains(pos)
                        || isCrossed(branches, pos, i)
                        || !isStackEmpty(target)) {
                    continue;
                }
                candidates.add(pos);
                mandatory.add(pos);
            }
        }

        List<Integer> cuts = new ArrayList<>();
        int chunkStart = 0;
        int chunkStartSize = 0;
        int lastCandidate = -1;
        for (int pos : candidates) {
            if (sizes[pos] - chunkStartSize > MAX_CHUNK_SIZE) {
                if (lastCandidate <= chunkStart) {
                    return null;
                }
                cuts.add(lastCandidate);
                chunkStart = lastCandidate;
                chunkStartSize = sizes[lastCandidate];
                if (sizes[pos] - chunkStartSize > MAX_CHUNK_SIZE) {
                    return null;
                }
            }
            if (!mandatory.contains(pos)) {
                lastCandidate = pos;
            } else if (pos > chunkStart) {
                cuts.add(pos);
                chunkStart = pos;
                chunkStartSize = sizes[pos];
            }
        }
        int totalSize = sizes[sizes.length - 1];
        if (totalSize - chunkStartSize > MAX_CHUNK_SIZE) {
            if (lastCandidate <= chunkStart) {
                return null;
            }
            cuts.add(lastCandidate);
            if (totalSize - sizes[lastCandidate] > MAX_CHUNK_SIZE) {
                return null;
            }
        }
        if (cuts.isEmpty()) {
            return null;
        }

        int[] starts = new int[cuts.size() + 1];
        for (int k = 0; k < cuts.size(); k++) {
            starts[k + 1] = cuts.get(k) + 1;
        }
        // A switch target in another chunk must be the label that ends a chunk.
        for (int i = 0; i < instructions.size(); i++) {
            var ins = instructions.get(i);
            if (ins.opcode() != CompilerOpCode.SWITCH) {
                continue;
            }
            for (int t = 0; t < ins.operandCount(); t++) {
                Integer pos = labelPositions.get(ins.operand(t));
                if (pos == null) {
                    return null;
                }
                if (chunkOf(starts, pos) != chunkOf(starts, i)
                        && Arrays.binarySearch(starts, pos + 1) < 0) {
                    return null;
                }
            }
        }
        return starts;
    }

    // True if a branch other than a forward one from skipSwitch jumps over the end of pos.
    private static boolean isCrossed(List<int[]> branches, int pos, int skipSwitch) {
        for (int[] branch : branches) {
            int from = Math.min(branch[0], branch[1]);
            int to = Math.max(branch[0], branch[1]);
            if (from != skipSwitch && from <= pos && pos < to) {
                return true;
            }
        }
        return false;
    }

    private boolean isStackEmpty(long label) {
        Frame<BasicValue> frame = labelFrames.get(label);
        return frame != null && frame.getStackSize() == 0;
    }

    private Plan planTrampoline(int totalSize, List<int[]> branches) {
        TreeSet<Integer> candidates = new TreeSet<>();
        Map<Integer, Type[]> stacks = new HashMap<>();
        candidates.add(0);
        stacks.put(0, EMPTY_STACK);
        for (var label : labelPositions.entrySet()) {
            Type[] stack = spillableStack(label.getKey());
            if (stack != null) {
                candidates.add(label.getValue());
                stacks.put(label.getValue(), stack);
            }
        }

        // A try_table's declaration, range and handler must be in one chunk.
        for (int i = 0; i < instructions.size(); i++) {
            var ins = instructions.get(i);
            if (ins.opcode() != CompilerOpCode.TRY_CATCH_BLOCK) {
                continue;
            }
            Integer start = labelPositions.get(ins.operand(0));
            Integer end = labelPositions.get(ins.operand(1));
            Integer handler = labelPositions.get(ins.operand(2));
            if (start == null || end == null || handler == null) {
                return null;
            }
            int lo = Math.min(i, Math.min(start, Math.min(end, handler)));
            int hi = Math.max(i, Math.max(start, Math.max(end, handler)));
            candidates.subSet(lo + 1, hi + 1).clear();
        }

        int budget =
                MAX_CHUNK_SIZE - CHUNK_BASE_COST - Math.min(totalLocals, 16) * LOCAL_SYNC_COST * 2;
        // Smaller budgets place the starts differently, so retry before giving up.
        for (int attempt = 0; attempt < 3; attempt++) {
            int attemptBudget = budget - (budget / 4) * attempt;
            if (attemptBudget < 512) {
                break;
            }
            int[] starts = planTrampolineStarts(attemptBudget, candidates, branches);
            if (starts != null) {
                Type[][] entryStacks = new Type[starts.length][];
                for (int k = 0; k < starts.length; k++) {
                    entryStacks[k] = stacks.get(starts[k]);
                }
                return new Plan(totalSize, true, starts, entryStacks);
            }
        }
        return null;
    }

    private int[] planTrampolineStarts(
            int budget, NavigableSet<Integer> candidates, List<int[]> branches) {
        TreeSet<Integer> starts = new TreeSet<>();
        starts.add(0);
        int chunkBase = 0;
        int prev = -1;
        for (int c : candidates) {
            if (c == 0) {
                continue;
            }
            if (sizes[c - 1] - chunkBase > budget) {
                if (prev > starts.last()) {
                    starts.add(prev);
                    chunkBase = sizes[prev - 1];
                }
                if (sizes[c - 1] - chunkBase > budget) {
                    starts.add(c);
                    chunkBase = sizes[c - 1];
                }
            }
            prev = c;
        }

        // Make every branch into another chunk land on a start, and every chunk fit.
        for (int round = 0; ; round++) {
            for (int pass = 0; ; pass++) {
                List<Integer> forced = new ArrayList<>();
                for (int[] branch : branches) {
                    int sourceChunk = starts.floor(branch[0]);
                    int targetChunk = starts.floor(branch[1]);
                    if (sourceChunk != targetChunk && targetChunk != branch[1]) {
                        forced.add(branch[1]);
                    }
                }
                if (forced.isEmpty()) {
                    break;
                }
                for (int target : forced) {
                    if (!candidates.contains(target)) {
                        return null;
                    }
                    starts.add(target);
                }
                if (starts.size() > MAX_TRAMPOLINE_CHUNKS || pass > 64) {
                    return null;
                }
            }

            List<Integer> list = new ArrayList<>(starts);
            int oversized = -1;
            for (int k = 0; k < list.size(); k++) {
                int to = k + 1 < list.size() ? list.get(k + 1) : instructions.size();
                if (trampolineChunkCost(list.get(k), to) > MAX_CHUNK_SIZE) {
                    oversized = k;
                    break;
                }
            }
            if (oversized < 0) {
                break;
            }
            if (round > 64 || starts.size() > MAX_TRAMPOLINE_CHUNKS) {
                return null;
            }

            // Split it at the last start that fits, or else at the first one.
            int from = list.get(oversized);
            int to = oversized + 1 < list.size() ? list.get(oversized + 1) : instructions.size();
            var inside = candidates.subSet(from + 1, to);
            if (inside.isEmpty()) {
                return null;
            }
            int split = inside.first();
            for (int c : inside) {
                if (trampolineChunkCost(from, c) > MAX_CHUNK_SIZE) {
                    break;
                }
                split = c;
            }
            starts.add(split);
        }

        if (starts.size() < 2 || starts.size() > MAX_TRAMPOLINE_CHUNKS) {
            return null;
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    // Bytecode of [from, to) plus loading and storing its locals.
    private int trampolineChunkCost(int from, int to) {
        var slice = instructions.subList(from, to);
        boolean[] touched = localsUsed(slice, true);
        boolean[] written = localsUsed(slice, false);
        int synced = 0;
        for (int i = 0; i < totalLocals; i++) {
            synced += (touched[i] ? 1 : 0) + (written[i] ? 1 : 0);
        }
        int bytecode = sizes[to - 1] - (from == 0 ? 0 : sizes[from - 1]);
        return bytecode + CHUNK_BASE_COST + synced * LOCAL_SYNC_COST;
    }

    // Locals the slice writes, and also those it reads if withReads.
    private boolean[] localsUsed(List<CompilerInstruction> slice, boolean withReads) {
        boolean[] used = new boolean[totalLocals];
        for (var ins : slice) {
            switch (ins.opcode()) {
                case LOCAL_GET:
                    if (withReads) {
                        used[(int) ins.operand(0)] = true;
                    }
                    break;
                case LOCAL_SET:
                case LOCAL_TEE:
                    used[(int) ins.operand(0)] = true;
                    break;
                default:
                    break;
            }
        }
        return used;
    }

    // Operand-stack types at the label if a trampoline chunk can start there, else null.
    private Type[] spillableStack(long label) {
        Frame<BasicValue> frame = labelFrames.get(label);
        if (frame == null || frame.getStackSize() > MAX_SPILL) {
            return null;
        }
        Type[] stack = new Type[frame.getStackSize()];
        for (int i = 0; i < stack.length; i++) {
            BasicValue value = frame.getStack(i);
            Type valueType = value == null ? null : value.getType();
            if (valueType == null) {
                return null;
            }
            switch (valueType.getSort()) {
                case Type.INT:
                case Type.LONG:
                case Type.FLOAT:
                case Type.DOUBLE:
                    stack[i] = valueType;
                    break;
                default:
                    return null;
            }
        }
        return stack;
    }

    private static int chunkOf(int[] starts, int pos) {
        int i = Arrays.binarySearch(starts, pos);
        return i >= 0 ? i : -i - 2;
    }

    private int labelChunk(int[] starts, long label) {
        return chunkOf(starts, labelPositions.get(label));
    }

    private List<CompilerInstruction> chunkInstructions(int[] starts, int chunk) {
        int end = chunk + 1 < starts.length ? starts[chunk + 1] : instructions.size();
        return instructions.subList(starts[chunk], end);
    }

    // Chunk 0 is the function's method; each chunk ends by calling the next.
    private void emitDirect(int[] starts, ClassVisitor classWriter, InstructionAdapter asm) {
        var chunkType = directChunkType();
        emitLocalsDefaultInit(ctx, type, body, asm);
        emitDirectChunk(starts, 0, chunkType, asm);
        for (int k = 1; k < starts.length; k++) {
            int chunk = k;
            emitFunction(
                    classWriter,
                    funcName + "_chunk_" + chunk,
                    chunkType,
                    true,
                    chunkAsm -> emitDirectChunk(starts, chunk, chunkType, chunkAsm));
        }
    }

    private void emitDirectChunk(
            int[] starts, int chunk, MethodType chunkType, InstructionAdapter asm) {
        Map<Long, Label> stubs = new HashMap<>();
        Set<Long> stubsEmitted = new HashSet<>();
        for (var ins : chunkInstructions(starts, chunk)) {
            if (ins.opcode() == CompilerOpCode.SWITCH
                    && ins.operands().anyMatch(target -> labelChunk(starts, target) != chunk)) {
                emitDirectSwitch(starts, chunk, ins, chunkType, stubs, stubsEmitted, asm);
            } else {
                emitInstruction(ctx, labels, visitedTargets, ins, asm);
            }
        }
        if (chunk + 1 < starts.length) {
            emitDirectChunkCall(chunk + 1, chunkType, asm);
        }
    }

    // Targets in other chunks jump to a stub that calls the chunk after the target label.
    private void emitDirectSwitch(
            int[] starts,
            int chunk,
            CompilerInstruction ins,
            MethodType chunkType,
            Map<Long, Label> stubs,
            Set<Long> stubsEmitted,
            InstructionAdapter asm) {
        int n = ins.operandCount() - 1;
        Label[] table = new Label[n];
        for (int i = 0; i < n; i++) {
            table[i] = directSwitchLabel(starts, chunk, ins.operand(i), stubs);
        }
        Label defaultLabel = directSwitchLabel(starts, chunk, ins.operand(n), stubs);
        asm.tableswitch(0, n - 1, defaultLabel, table);

        for (long target : ins.operands().distinct().toArray()) {
            int targetChunk = labelChunk(starts, target);
            if (targetChunk != chunk && stubsEmitted.add(target)) {
                asm.mark(stubs.get(target));
                emitDirectChunkCall(targetChunk + 1, chunkType, asm);
            }
        }
    }

    private Label directSwitchLabel(int[] starts, int chunk, long target, Map<Long, Label> stubs) {
        if (labelChunk(starts, target) == chunk) {
            return labels.get(target);
        }
        return stubs.computeIfAbsent(target, t -> new Label());
    }

    // (params..., Memory, Instance, locals...)
    private MethodType directChunkType() {
        var params = new ArrayList<Class<?>>(List.of(jvmParameterTypes(type)));
        params.add(Memory.class);
        params.add(Instance.class);
        for (ValType local : body.localTypes()) {
            params.add(jvmType(local));
        }
        return methodType(jvmReturnType(type), params.toArray(new Class<?>[0]));
    }

    private void emitDirectChunkCall(int chunk, MethodType chunkType, InstructionAdapter asm) {
        int paramCount = type.params().size();
        for (int i = 0; i < paramCount; i++) {
            asm.load(ctx.localSlotIndex(i), asmType(localType(type, body, i)));
        }
        asm.load(ctx.memorySlot(), OBJECT_TYPE);
        asm.load(ctx.instanceSlot(), OBJECT_TYPE);
        for (int i = paramCount; i < totalLocals; i++) {
            asm.load(ctx.localSlotIndex(i), asmType(localType(type, body, i)));
        }
        asm.invokestatic(
                chunkClass,
                funcName + "_chunk_" + chunk,
                chunkType.toMethodDescriptorString(),
                false);
        asm.areturn(returnType);
    }

    // The function's method copies its params into the frame and calls chunks until one returns.
    private void emitTrampoline(
            Plan plan, ClassVisitor classWriter, Context entryCtx, InstructionAdapter asm) {
        int chunks = plan.starts.length;
        int maxSpill = 0;
        for (Type[] stack : plan.entryStacks) {
            maxSpill = Math.max(maxSpill, stack.length);
        }
        var chunkType = methodType(jvmReturnType(type), long[].class, Memory.class, Instance.class);

        // slots from trySaveBaseSlot() on are free
        int frame = entryCtx.trySaveBaseSlot() + 8;
        asm.iconst(spillBase + maxSpill);
        asm.newarray(LONG_TYPE);
        asm.store(frame, LONG_ARRAY_TYPE);
        for (int i = 0; i < type.params().size(); i++) {
            var localType = localType(type, body, i);
            asm.load(frame, LONG_ARRAY_TYPE);
            asm.iconst(i);
            asm.load(entryCtx.localSlotIndex(i), asmType(localType));
            emitJvmToLong(asm, localType);
            asm.astore(LONG_TYPE);
        }

        Label loop = new Label();
        Label invalidPc = new Label();
        Label[] cases = new Label[chunks];
        for (int k = 0; k < chunks; k++) {
            cases[k] = new Label();
        }
        asm.mark(loop);
        emitInvokeStatic(asm, CHECK_INTERRUPTION);
        asm.load(frame, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.aload(LONG_TYPE);
        asm.visitInsn(Opcodes.L2I);
        asm.tableswitch(0, chunks - 1, invalidPc, cases);
        for (int k = 0; k < chunks; k++) {
            asm.mark(cases[k]);
            asm.load(frame, LONG_ARRAY_TYPE);
            asm.load(entryCtx.memorySlot(), OBJECT_TYPE);
            asm.load(entryCtx.instanceSlot(), OBJECT_TYPE);
            asm.invokestatic(
                    chunkClass, funcName + "_tc_" + k, chunkType.toMethodDescriptorString(), false);
            asm.load(frame, LONG_ARRAY_TYPE);
            asm.iconst(pcIndex);
            asm.aload(LONG_TYPE);
            asm.visitInsn(Opcodes.L2I);
            asm.iconst(PC_DONE);
            Label next = new Label();
            asm.ificmpne(next);
            asm.areturn(returnType);
            asm.mark(next);
            if (returnType.getSize() == 2) {
                asm.pop2();
            } else if (returnType.getSize() == 1) {
                asm.pop();
            }
            asm.goTo(loop);
        }
        asm.mark(invalidPc);
        emitDefaultReturnValue(asm);
        asm.areturn(returnType);

        for (int k = 0; k < chunks; k++) {
            int chunk = k;
            emitFunction(
                    classWriter,
                    funcName + "_tc_" + chunk,
                    chunkType,
                    true,
                    chunkAsm -> emitTrampolineChunk(plan, chunk, chunkAsm));
        }
    }

    // (long[] frame, Memory, Instance): loads its locals, runs, stores them and sets the PC.
    private void emitTrampolineChunk(Plan plan, int chunk, InstructionAdapter asm) {
        var slice = chunkInstructions(plan.starts, chunk);
        asm.load(0, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.lconst(PC_DONE);
        asm.astore(LONG_TYPE);

        // a write may be on one path only, so written locals are loaded too
        boolean[] touched = localsUsed(slice, true);
        boolean[] written = localsUsed(slice, false);
        for (int i = 0; i < totalLocals; i++) {
            if (touched[i]) {
                var localType = localType(type, body, i);
                asm.load(0, LONG_ARRAY_TYPE);
                asm.iconst(i);
                asm.aload(LONG_TYPE);
                emitLongToJvm(asm, localType);
                asm.store(ctx.localSlotIndex(i), asmType(localType));
            }
        }
        Type[] stack = plan.entryStacks[chunk];
        for (int i = 0; i < stack.length; i++) {
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(spillBase + i);
            asm.aload(LONG_TYPE);
            emitLongToStackValue(asm, stack[i]);
        }

        Label exit = new Label();
        Map<Integer, Label> stubs = new HashMap<>();
        for (var ins : slice) {
            emitTrampolineInstruction(plan, chunk, ins, exit, stubs, asm);
        }
        emitTransition(plan, chunk + 1 < plan.starts.length ? chunk + 1 : PC_DONE, exit, asm);
        for (var stub : stubs.entrySet()) {
            asm.mark(stub.getValue());
            emitTransition(plan, stub.getKey(), exit, asm);
        }

        asm.mark(exit);
        for (int i = 0; i < totalLocals; i++) {
            if (written[i]) {
                var localType = localType(type, body, i);
                asm.load(0, LONG_ARRAY_TYPE);
                asm.iconst(i);
                asm.load(ctx.localSlotIndex(i), asmType(localType));
                emitJvmToLong(asm, localType);
                asm.astore(LONG_TYPE);
            }
        }
        asm.load(0, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.load(ctx.trySaveBaseSlot() + 8, INT_TYPE);
        asm.visitInsn(Opcodes.I2L);
        asm.astore(LONG_TYPE);
        emitDefaultReturnValue(asm);
        asm.areturn(returnType);
    }

    // Like emitInstruction, but a branch to another chunk becomes a transition.
    private void emitTrampolineInstruction(
            Plan plan,
            int chunk,
            CompilerInstruction ins,
            Label exit,
            Map<Integer, Label> stubs,
            InstructionAdapter asm) {
        int[] starts = plan.starts;
        switch (ins.opcode()) {
            case GOTO:
            case IFEQ:
            case IFNE:
                {
                    int target = labelChunk(starts, ins.operand(0));
                    if (target == chunk) {
                        break;
                    }
                    Label notTaken = new Label();
                    if (ins.opcode() == CompilerOpCode.IFEQ) {
                        asm.ifne(notTaken);
                    } else if (ins.opcode() == CompilerOpCode.IFNE) {
                        asm.ifeq(notTaken);
                    }
                    emitTransition(plan, target, exit, asm);
                    if (ins.opcode() != CompilerOpCode.GOTO) {
                        asm.mark(notTaken);
                    }
                    return;
                }
            case SWITCH:
                {
                    if (ins.operands().allMatch(target -> labelChunk(starts, target) == chunk)) {
                        break;
                    }
                    int n = ins.operandCount() - 1;
                    Label[] table = new Label[n];
                    for (int i = 0; i < n; i++) {
                        table[i] = trampolineSwitchLabel(starts, chunk, ins.operand(i), stubs);
                    }
                    Label defaultLabel =
                            trampolineSwitchLabel(starts, chunk, ins.operand(n), stubs);
                    asm.tableswitch(0, n - 1, defaultLabel, table);
                    return;
                }
            default:
                break;
        }
        emitInstruction(ctx, labels, visitedTargets, ins, asm);
    }

    // Targets in other chunks share one stub per chunk.
    private Label trampolineSwitchLabel(
            int[] starts, int chunk, long target, Map<Integer, Label> stubs) {
        int targetChunk = labelChunk(starts, target);
        if (targetChunk == chunk) {
            return labels.get(target);
        }
        return stubs.computeIfAbsent(targetChunk, k -> new Label());
    }

    // Spills the operand stack into the frame, sets the PC and jumps to the chunk's exit.
    private void emitTransition(Plan plan, int targetChunk, Label exit, InstructionAdapter asm) {
        Type[] stack = targetChunk == PC_DONE ? EMPTY_STACK : plan.entryStacks[targetChunk];
        int temp = ctx.trySaveBaseSlot() + 16;
        for (int i = stack.length - 1; i >= 0; i--) {
            emitStackValueToLong(asm, stack[i]);
            asm.store(temp + i * 2, LONG_TYPE);
        }
        for (int i = 0; i < stack.length; i++) {
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(spillBase + i);
            asm.load(temp + i * 2, LONG_TYPE);
            asm.astore(LONG_TYPE);
        }
        asm.iconst(targetChunk);
        asm.store(ctx.trySaveBaseSlot() + 8, INT_TYPE);
        asm.goTo(exit);
    }

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
            default:
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
            default:
        }
    }

    private void emitDefaultReturnValue(InstructionAdapter asm) {
        switch (returnType.getSort()) {
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

    // Over-estimates bytecode size.
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
            size += 16 + labels.length * 4;
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            size += 12 + labels.length * 8;
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            size += 4;
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }
    }
}
