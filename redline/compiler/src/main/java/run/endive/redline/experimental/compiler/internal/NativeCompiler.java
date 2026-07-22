package run.endive.redline.experimental.compiler.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import run.endive.redline.experimental.api.internal.CtxBuffer;
import run.endive.redline.experimental.api.internal.TypeMapUtils;
import run.endive.redline.experimental.bridge.internal.CraneliftBridge;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.AnnotatedInstruction;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.OpCode;
import run.endive.wasm.types.ValType;

/**
 * Thin orchestrator that compiles Wasm functions to native code via Cranelift.
 *
 * <p>Walks instructions, delegates opcodes to {@link NativeEmitters},
 * handles control flow inline. Dead code after unconditional transfers
 * is tracked via the control stack's {@code unreachable} flag.
 */
public final class NativeCompiler {

    private final CraneliftBridge bridge;
    private final String triple;
    private final WasmModule module;
    private final int numImports;
    private final int[] canonicalTypeMap;

    private NativeCompiler(CraneliftBridge bridge, String triple, WasmModule module) {
        this.bridge = bridge;
        this.triple = triple;
        this.module = module;
        this.numImports =
                (int)
                        module.importSection().stream()
                                .filter(i -> i.importType() == ExternalType.FUNCTION)
                                .count();
        this.canonicalTypeMap = TypeMapUtils.buildCanonicalTypeMap(module);
    }

    // --- Control frame ---

    private static final class ControlFrame {
        enum Kind {
            BLOCK,
            LOOP,
            IF,
            FUNCTION
        }

        final Kind kind;
        final int mergeBlock;
        final int loopBlock;
        int elseBlock;
        final int[] mergeParamIds;
        final FunctionType blockType;
        final int stackHeight;
        int[] elseParamIds; // IF only: block param IDs for else block
        boolean unreachable;
        boolean hasElse;

        ControlFrame(
                Kind kind,
                int mergeBlock,
                int loopBlock,
                int elseBlock,
                int[] mergeParamIds,
                FunctionType blockType,
                int stackHeight) {
            this.kind = kind;
            this.mergeBlock = mergeBlock;
            this.loopBlock = loopBlock;
            this.elseBlock = elseBlock;
            this.mergeParamIds = mergeParamIds;
            this.blockType = blockType;
            this.stackHeight = stackHeight;
        }

        int branchTarget() {
            return kind == Kind.LOOP ? loopBlock : mergeBlock;
        }

        int branchArgCount() {
            return kind == Kind.LOOP ? blockType.params().size() : blockType.returns().size();
        }
    }

    // --- Compilation ---

    private static final int THREAD_COUNT =
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREAD_COUNT);

    public static byte[][] compileAll(String triple, WasmModule module) {
        return new NativeCompiler(null, triple, module).compileAll((boolean[]) null);
    }

    /**
     * Compiles functions to native code. If {@code filter} is non-null, only
     * functions where {@code filter[numImports + bodyIndex]} is {@code true}
     * are compiled; others get {@code null} entries.
     */
    private byte[][] compileAll(boolean[] filter) {
        int count = module.codeSection().functionBodyCount();
        byte[][] results = new byte[count][];
        int threads = Math.min(THREAD_COUNT, count);
        if (threads < 1) {
            threads = 1;
        }

        int chunkSize = (count + threads - 1) / threads;
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int start = t * chunkSize;
            int end = Math.min(start + chunkSize, count);
            if (start >= count) {
                break;
            }

            futures.add(
                    POOL.submit(
                            () -> {
                                try (var threadBridge = new CraneliftBridge()) {
                                    threadBridge.init(triple);
                                    var threadCompiler =
                                            new NativeCompiler(threadBridge, triple, module);
                                    for (int i = start; i < end; i++) {
                                        if (filter != null && !filter[numImports + i]) {
                                            continue;
                                        }
                                        try {
                                            results[i] = threadCompiler.compileFunction(i);
                                        } catch (RuntimeException e) {
                                            throw new WasmEngineException(
                                                    "Failed to compile function "
                                                            + i
                                                            + ": "
                                                            + e.getMessage(),
                                                    e);
                                        }
                                    }
                                }
                                return null;
                            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw new WasmEngineException("Parallel compilation failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WasmEngineException("Compilation interrupted", e);
            }
        }
        return results;
    }

    private byte[] compileFunction(int bodyIndex) {
        var body = module.codeSection().getFunctionBody(bodyIndex);
        int typeIdx = module.functionSection().getFunctionType(bodyIndex);
        var funcType = (FunctionType) module.typeSection().getType(typeIdx);

        // Multi-return: functions with >1 return write results to argsBuffer
        // and return a single i64 (dummy). Single-return uses registers (fast path).
        boolean multiReturn = funcType.returns().size() > 1;

        // --- Setup function ---
        bridge.exports().createFunction();

        bridge.exports().addParamType(CraneliftBridge.TYPE_I64); // memBase
        bridge.exports().addParamType(CraneliftBridge.TYPE_I64); // ctxPtr
        for (ValType param : funcType.params()) {
            bridge.exports().addParamType(EmitContext.valTypeToBridgeType(param));
        }
        if (multiReturn) {
            // Multi-return: return single i64 dummy (actual values in argsBuffer)
            bridge.exports().addReturnType(CraneliftBridge.TYPE_I64);
        } else {
            for (ValType ret : funcType.returns()) {
                bridge.exports().addReturnType(EmitContext.valTypeToBridgeType(ret));
            }
        }

        bridge.exports().buildFunction();

        // Create entry block
        int entry = bridge.exports().createBlock();
        bridge.exports().appendBlockParamsForFuncParams(entry);
        bridge.exports().switchToBlock(entry);

        // Get params
        int memBaseParam = bridge.exports().funcParam(entry, 0);
        int ctxPtrParam = bridge.exports().funcParam(entry, 1);
        int[] paramVals = new int[funcType.params().size()];
        for (int i = 0; i < paramVals.length; i++) {
            paramVals[i] = bridge.exports().funcParam(entry, i + 2);
        }

        // memBase and ctxPtr as variables
        int memBaseVar = bridge.exports().declareVar(CraneliftBridge.TYPE_I64);
        bridge.exports().defVar(memBaseVar, memBaseParam);
        int ctxPtrVar = bridge.exports().declareVar(CraneliftBridge.TYPE_I64);
        bridge.exports().defVar(ctxPtrVar, ctxPtrParam);

        // Locals
        int numParams = funcType.params().size();
        int numBodyLocals = body.localTypes().size();
        int totalLocals = numParams + numBodyLocals;
        int[] localVars = new int[totalLocals];
        for (int i = 0; i < numParams; i++) {
            localVars[i] =
                    bridge.exports()
                            .declareVar(EmitContext.valTypeToBridgeType(funcType.params().get(i)));
            bridge.exports().defVar(localVars[i], paramVals[i]);
        }
        for (int i = 0; i < numBodyLocals; i++) {
            ValType localType = body.localTypes().get(i);
            localVars[numParams + i] =
                    bridge.exports().declareVar(EmitContext.valTypeToBridgeType(localType));
            int zero = emitZero(localType);
            bridge.exports().defVar(localVars[numParams + i], zero);
        }

        // --- Stack depth guard (wasmtime-style) ---
        // At function entry: read RSP via get_stack_pointer.
        // If STACK_LIMIT == 0 (first call): store RSP - 512KB as limit.
        // Otherwise: if RSP < STACK_LIMIT → trap "call stack exhausted".
        // Cost: 1 load + 1 compare + 1 branch (predicted not-taken).
        {
            int sp = bridge.exports().emitGetStackPointer();
            int zero = bridge.exports().emitIconst32(0);
            int stackLimit =
                    bridge.exports()
                            .emitLoadI64(
                                    bridge.exports().useVar(ctxPtrVar),
                                    zero,
                                    CtxBuffer.STACK_LIMIT);

            // Check if limit needs initialization (== 0)
            int zeroI64 = bridge.exports().emitIconst64(0, 0);
            int needsInit = bridge.exports().emitIcmp(0, stackLimit, zeroI64); // EQ
            int initBlock = bridge.exports().createBlock();
            int checkBlock = bridge.exports().createBlock();
            bridge.exports().emitBrif(needsInit, initBlock, checkBlock);

            // Init block: store SP - 512KB as limit, then continue
            bridge.exports().switchToBlock(initBlock);
            int reserve = bridge.exports().emitIconst64(524288, 0); // 512KB
            int newLimit = bridge.exports().emitIsub(sp, reserve);
            bridge.exports()
                    .emitStoreI64(
                            bridge.exports().useVar(ctxPtrVar),
                            bridge.exports().emitIconst32(0),
                            newLimit,
                            CtxBuffer.STACK_LIMIT);
            bridge.exports().emitJump(checkBlock);

            // Check block: compare SP against limit
            bridge.exports().switchToBlock(checkBlock);
            // Re-load limit (may have been just written)
            int limit2 =
                    bridge.exports()
                            .emitLoadI64(
                                    bridge.exports().useVar(ctxPtrVar),
                                    bridge.exports().emitIconst32(0),
                                    CtxBuffer.STACK_LIMIT);
            int exhausted = bridge.exports().emitIcmp(3, sp, limit2); // LT unsigned
            int trapBlock = bridge.exports().createBlock();
            int okBlock = bridge.exports().createBlock();
            bridge.exports().emitBrif(exhausted, trapBlock, okBlock);

            // Trap block: write trap code and return
            bridge.exports().switchToBlock(trapBlock);
            int ctxVal = bridge.exports().useVar(ctxPtrVar);
            int zeroT = bridge.exports().emitIconst32(0);
            int code = bridge.exports().emitIconst32(CtxBuffer.TRAP_CALL_STACK_EXHAUSTED);
            bridge.exports().emitStoreI32(ctxVal, zeroT, code, CtxBuffer.TRAP_CODE);
            if (multiReturn || funcType.returns().isEmpty()) {
                if (funcType.returns().isEmpty()) {
                    bridge.exports().emitReturnVoid();
                } else {
                    bridge.exports().emitReturn(bridge.exports().emitIconst64(0, 0));
                }
            } else {
                bridge.exports().emitReturn(emitZero(funcType.returns().get(0)));
            }

            bridge.exports().switchToBlock(okBlock);
        }

        // --- Interrupt check ---
        // If INTERRUPT_FLAG != 0 → trap immediately.
        // Cost: 1 load + 1 compare + 1 branch (predicted not-taken).
        {
            int flag =
                    bridge.exports()
                            .emitLoadI64(
                                    bridge.exports().useVar(ctxPtrVar),
                                    bridge.exports().emitIconst32(0),
                                    CtxBuffer.INTERRUPT_FLAG);
            int zeroI64 = bridge.exports().emitIconst64(0, 0);
            int interrupted = bridge.exports().emitIcmp(1, flag, zeroI64); // NE
            int trapBlock = bridge.exports().createBlock();
            int okBlock = bridge.exports().createBlock();
            bridge.exports().emitBrif(interrupted, trapBlock, okBlock);

            bridge.exports().switchToBlock(trapBlock);
            int ctxVal = bridge.exports().useVar(ctxPtrVar);
            int zeroT = bridge.exports().emitIconst32(0);
            int code = bridge.exports().emitIconst32(CtxBuffer.TRAP_INTERRUPTED);
            bridge.exports().emitStoreI32(ctxVal, zeroT, code, CtxBuffer.TRAP_CODE);
            if (multiReturn || funcType.returns().isEmpty()) {
                if (funcType.returns().isEmpty()) {
                    bridge.exports().emitReturnVoid();
                } else {
                    bridge.exports().emitReturn(bridge.exports().emitIconst64(0, 0));
                }
            } else {
                bridge.exports().emitReturn(emitZero(funcType.returns().get(0)));
            }

            bridge.exports().switchToBlock(okBlock);
        }

        // --- Create emit context ---
        var valueStack = new NativeValueStack();
        var ctx =
                new EmitContext(
                        bridge,
                        valueStack,
                        module,
                        numImports,
                        funcType,
                        localVars,
                        memBaseVar,
                        ctxPtrVar,
                        new HashMap<>(),
                        multiReturn,
                        canonicalTypeMap);

        // --- Emission loop ---
        Deque<ControlFrame> controlStack = new ArrayDeque<>();

        // Implicit function-level frame
        controlStack.push(
                new ControlFrame(ControlFrame.Kind.FUNCTION, -1, -1, -1, new int[0], funcType, 0));
        List<AnnotatedInstruction> instructions = body.instructions();
        for (int idx = 0; idx < instructions.size(); idx++) {
            AnnotatedInstruction ins = instructions.get(idx);

            if (!controlStack.isEmpty() && controlStack.peek().unreachable) {
                switch (ins.opcode()) {
                    case END:
                    case ELSE:
                        // Process normally — these exit the unreachable state
                        break;
                    case BLOCK:
                    case LOOP:
                    case IF:
                        // Push dummy frame to keep control stack balanced
                        controlStack.push(
                                new ControlFrame(
                                        ins.opcode() == OpCode.IF
                                                ? ControlFrame.Kind.IF
                                                : ins.opcode() == OpCode.LOOP
                                                        ? ControlFrame.Kind.LOOP
                                                        : ControlFrame.Kind.BLOCK,
                                        -1,
                                        -1,
                                        -1,
                                        new int[0],
                                        FunctionType.empty(),
                                        valueStack.size()));
                        controlStack.peek().unreachable = true;
                        continue;
                    default:
                        continue; // skip all other instructions
                }
            }

            if (ins.opcode() == OpCode.END) {
                emitEnd(ctx, controlStack);
                continue;
            }

            emitInstruction(ctx, ins, controlStack);
        }

        bridge.exports().sealAllBlocks();
        return bridge.compile();
    }

    // --- Block type decoding ---

    private FunctionType decodeBlockType(AnnotatedInstruction ins) {
        long typeId = ins.operands()[0];
        if (typeId == 0x40) {
            return FunctionType.empty();
        }
        if (ValType.isValid(typeId)) {
            return FunctionType.returning(ValType.builder().fromId(typeId).build());
        }
        return (FunctionType) module.typeSection().getType((int) typeId);
    }

    // --- Control stack helpers ---

    private static ControlFrame getControlFrame(Deque<ControlFrame> controlStack, int depth) {
        Iterator<ControlFrame> it = controlStack.iterator();
        for (int i = 0; i < depth; i++) {
            it.next();
        }
        return it.next();
    }

    private int[] appendBlockParams(int blockId, List<ValType> types) {
        int[] paramIds = new int[types.size()];
        for (int i = 0; i < types.size(); i++) {
            paramIds[i] =
                    bridge.exports()
                            .appendBlockParam(
                                    blockId, EmitContext.valTypeToBridgeType(types.get(i)));
        }
        return paramIds;
    }

    private void emitJumpToBlock(int blockId, int argCount, NativeValueStack valueStack) {
        if (argCount == 0) {
            bridge.exports().emitJump(blockId);
        } else if (argCount == 1) {
            bridge.exports().emitJumpWithArg(blockId, valueStack.pop());
        } else {
            int[] args = new int[argCount];
            for (int i = argCount - 1; i >= 0; i--) {
                args[i] = valueStack.pop();
            }
            for (int a : args) {
                bridge.exports().pushCallArg(a);
            }
            bridge.exports().emitJumpWithArgs(blockId);
        }
    }

    private void emitDeadPredecessor(int targetBlock, List<ValType> types) {
        int deadBlock = bridge.exports().createBlock();
        bridge.exports().switchToBlock(deadBlock);
        if (types.size() == 1) {
            bridge.exports().emitJumpWithArg(targetBlock, emitZero(types.get(0)));
        } else {
            for (ValType t : types) {
                bridge.exports().pushCallArg(emitZero(t));
            }
            bridge.exports().emitJumpWithArgs(targetBlock);
        }
    }

    private int emitZero(ValType type) {
        if (type.equals(ValType.I32)) {
            return bridge.exports().emitIconst32(0);
        }
        if (type.equals(ValType.I64)) {
            return bridge.exports().emitIconst64(0, 0);
        }
        if (type.equals(ValType.F32)) {
            return bridge.exports().emitF32const(0);
        }
        if (type.equals(ValType.F64)) {
            return bridge.exports().emitF64const(0, 0);
        }
        // Reference types use i64 representation
        int op = type.opcode();
        if (op == ValType.ID.RefNull || op == ValType.ID.Ref) {
            return bridge.exports().emitIconst64(0, 0);
        }
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }

    // --- Instruction emission ---

    private void emitInstruction(
            EmitContext ctx, AnnotatedInstruction ins, Deque<ControlFrame> controlStack) {

        var valueStack = ctx.valueStack;

        switch (ins.opcode()) {
            // --- Constants ---
            case I32_CONST:
                NativeEmitters.emitI32Const(ctx, ins);
                break;
            case I64_CONST:
                NativeEmitters.emitI64Const(ctx, ins);
                break;
            case F32_CONST:
                NativeEmitters.emitF32Const(ctx, ins);
                break;
            case F64_CONST:
                NativeEmitters.emitF64Const(ctx, ins);
                break;

            // --- i32 Arithmetic ---
            case I32_ADD:
                NativeEmitters.emitI32BinaryOp(ctx, 0);
                break;
            case I32_SUB:
                NativeEmitters.emitI32BinaryOp(ctx, 1);
                break;
            case I32_MUL:
                NativeEmitters.emitI32BinaryOp(ctx, 2);
                break;
            case I32_DIV_S:
                NativeEmitters.emitSafeDiv(ctx, true, false, false);
                break;
            case I32_DIV_U:
                NativeEmitters.emitSafeDiv(ctx, false, false, false);
                break;
            case I32_REM_S:
                NativeEmitters.emitSafeDiv(ctx, true, true, false);
                break;
            case I32_REM_U:
                NativeEmitters.emitSafeDiv(ctx, false, true, false);
                break;
            case I32_AND:
                NativeEmitters.emitI32BinaryOp(ctx, 3);
                break;
            case I32_OR:
                NativeEmitters.emitI32BinaryOp(ctx, 4);
                break;
            case I32_XOR:
                NativeEmitters.emitI32BinaryOp(ctx, 5);
                break;
            case I32_SHL:
                NativeEmitters.emitI32BinaryOp(ctx, 6);
                break;
            case I32_SHR_S:
                NativeEmitters.emitI32BinaryOp(ctx, 7);
                break;
            case I32_SHR_U:
                NativeEmitters.emitI32BinaryOp(ctx, 8);
                break;
            case I32_ROTL:
                NativeEmitters.emitI32BinaryOp(ctx, 9);
                break;
            case I32_ROTR:
                NativeEmitters.emitI32BinaryOp(ctx, 10);
                break;
            case I32_CLZ:
                NativeEmitters.emitI32UnaryOp(ctx, 0);
                break;
            case I32_CTZ:
                NativeEmitters.emitI32UnaryOp(ctx, 1);
                break;
            case I32_POPCNT:
                NativeEmitters.emitI32UnaryOp(ctx, 2);
                break;

            // --- i32 Comparisons ---
            case I32_EQZ:
                NativeEmitters.emitI32UnaryOp(ctx, 3);
                break;
            case I32_EQ:
                NativeEmitters.emitIcmp(ctx, 0);
                break;
            case I32_NE:
                NativeEmitters.emitIcmp(ctx, 1);
                break;
            case I32_LT_S:
                NativeEmitters.emitIcmp(ctx, 2);
                break;
            case I32_LT_U:
                NativeEmitters.emitIcmp(ctx, 3);
                break;
            case I32_GT_S:
                NativeEmitters.emitIcmp(ctx, 4);
                break;
            case I32_GT_U:
                NativeEmitters.emitIcmp(ctx, 5);
                break;
            case I32_LE_S:
                NativeEmitters.emitIcmp(ctx, 6);
                break;
            case I32_LE_U:
                NativeEmitters.emitIcmp(ctx, 7);
                break;
            case I32_GE_S:
                NativeEmitters.emitIcmp(ctx, 8);
                break;
            case I32_GE_U:
                NativeEmitters.emitIcmp(ctx, 9);
                break;

            // --- i32 Extensions ---
            case I32_EXTEND_8_S:
                NativeEmitters.emitI32Extend8S(ctx);
                break;
            case I32_EXTEND_16_S:
                NativeEmitters.emitI32Extend16S(ctx);
                break;

            // --- i64 Arithmetic ---
            case I64_ADD:
                NativeEmitters.emitI32BinaryOp(ctx, 0);
                break;
            case I64_SUB:
                NativeEmitters.emitI32BinaryOp(ctx, 1);
                break;
            case I64_MUL:
                NativeEmitters.emitI32BinaryOp(ctx, 2);
                break;
            case I64_DIV_S:
                NativeEmitters.emitSafeDiv(ctx, true, false, true);
                break;
            case I64_DIV_U:
                NativeEmitters.emitSafeDiv(ctx, false, false, true);
                break;
            case I64_REM_S:
                NativeEmitters.emitSafeDiv(ctx, true, true, true);
                break;
            case I64_REM_U:
                NativeEmitters.emitSafeDiv(ctx, false, true, true);
                break;
            case I64_AND:
                NativeEmitters.emitI32BinaryOp(ctx, 3);
                break;
            case I64_OR:
                NativeEmitters.emitI32BinaryOp(ctx, 4);
                break;
            case I64_XOR:
                NativeEmitters.emitI32BinaryOp(ctx, 5);
                break;
            case I64_SHL:
                NativeEmitters.emitI32BinaryOp(ctx, 6);
                break;
            case I64_SHR_S:
                NativeEmitters.emitI32BinaryOp(ctx, 7);
                break;
            case I64_SHR_U:
                NativeEmitters.emitI32BinaryOp(ctx, 8);
                break;
            case I64_ROTL:
                NativeEmitters.emitI32BinaryOp(ctx, 9);
                break;
            case I64_ROTR:
                NativeEmitters.emitI32BinaryOp(ctx, 10);
                break;
            case I64_CLZ:
                NativeEmitters.emitI64UnaryOp(ctx, 0);
                break;
            case I64_CTZ:
                NativeEmitters.emitI64UnaryOp(ctx, 1);
                break;
            case I64_POPCNT:
                NativeEmitters.emitI64UnaryOp(ctx, 2);
                break;

            // --- i64 Comparisons ---
            case I64_EQZ:
                NativeEmitters.emitI64UnaryOp(ctx, 3);
                break;
            case I64_EQ:
                NativeEmitters.emitIcmp(ctx, 0);
                break;
            case I64_NE:
                NativeEmitters.emitIcmp(ctx, 1);
                break;
            case I64_LT_S:
                NativeEmitters.emitIcmp(ctx, 2);
                break;
            case I64_LT_U:
                NativeEmitters.emitIcmp(ctx, 3);
                break;
            case I64_GT_S:
                NativeEmitters.emitIcmp(ctx, 4);
                break;
            case I64_GT_U:
                NativeEmitters.emitIcmp(ctx, 5);
                break;
            case I64_LE_S:
                NativeEmitters.emitIcmp(ctx, 6);
                break;
            case I64_LE_U:
                NativeEmitters.emitIcmp(ctx, 7);
                break;
            case I64_GE_S:
                NativeEmitters.emitIcmp(ctx, 8);
                break;
            case I64_GE_U:
                NativeEmitters.emitIcmp(ctx, 9);
                break;

            // --- i64 Extensions ---
            case I64_EXTEND_I32_S:
                NativeEmitters.emitI64ExtendI32S(ctx);
                break;
            case I64_EXTEND_I32_U:
                NativeEmitters.emitI64ExtendI32U(ctx);
                break;
            case I64_EXTEND_8_S:
                NativeEmitters.emitI64Extend8S(ctx);
                break;
            case I64_EXTEND_16_S:
                NativeEmitters.emitI64Extend16S(ctx);
                break;
            case I64_EXTEND_32_S:
                NativeEmitters.emitI64Extend32S(ctx);
                break;
            case I32_WRAP_I64:
                NativeEmitters.emitI32WrapI64(ctx);
                break;

            // --- Memory loads ---
            case I32_LOAD:
                NativeEmitters.emitLoad(ctx, ins, 0);
                break;
            case I64_LOAD:
                NativeEmitters.emitLoad(ctx, ins, 1);
                break;
            case F32_LOAD:
                NativeEmitters.emitLoad(ctx, ins, 2);
                break;
            case F64_LOAD:
                NativeEmitters.emitLoad(ctx, ins, 3);
                break;
            case I32_LOAD8_U:
                NativeEmitters.emitLoad(ctx, ins, 4);
                break;
            case I32_LOAD8_S:
                NativeEmitters.emitLoad(ctx, ins, 5);
                break;
            case I32_LOAD16_U:
                NativeEmitters.emitLoad(ctx, ins, 6);
                break;
            case I32_LOAD16_S:
                NativeEmitters.emitLoad(ctx, ins, 7);
                break;
            case I64_LOAD8_U:
                NativeEmitters.emitLoad(ctx, ins, 8);
                break;
            case I64_LOAD8_S:
                NativeEmitters.emitLoad(ctx, ins, 9);
                break;
            case I64_LOAD16_U:
                NativeEmitters.emitLoad(ctx, ins, 10);
                break;
            case I64_LOAD16_S:
                NativeEmitters.emitLoad(ctx, ins, 11);
                break;
            case I64_LOAD32_U:
                NativeEmitters.emitLoad(ctx, ins, 12);
                break;
            case I64_LOAD32_S:
                NativeEmitters.emitLoad(ctx, ins, 13);
                break;

            // --- Memory stores ---
            case I32_STORE:
                NativeEmitters.emitStore(ctx, ins, 0);
                break;
            case I64_STORE:
                NativeEmitters.emitStore(ctx, ins, 1);
                break;
            case F32_STORE:
                NativeEmitters.emitStore(ctx, ins, 2);
                break;
            case F64_STORE:
                NativeEmitters.emitStore(ctx, ins, 3);
                break;
            case I32_STORE8:
                NativeEmitters.emitStore(ctx, ins, 4);
                break;
            case I32_STORE16:
                NativeEmitters.emitStore(ctx, ins, 5);
                break;
            case I64_STORE8:
                NativeEmitters.emitStore(ctx, ins, 6);
                break;
            case I64_STORE16:
                NativeEmitters.emitStore(ctx, ins, 7);
                break;
            case I64_STORE32:
                NativeEmitters.emitStore(ctx, ins, 8);
                break;

            // --- Locals ---
            case LOCAL_GET:
                NativeEmitters.emitLocalGet(ctx, ins);
                break;
            case LOCAL_SET:
                NativeEmitters.emitLocalSet(ctx, ins);
                break;
            case LOCAL_TEE:
                NativeEmitters.emitLocalTee(ctx, ins);
                break;

            // --- Select ---
            case SELECT:
            case SELECT_T:
                NativeEmitters.emitSelect(ctx);
                break;

            // --- Globals ---
            case GLOBAL_GET:
                NativeEmitters.emitGlobalGet(ctx, ins);
                break;
            case GLOBAL_SET:
                NativeEmitters.emitGlobalSet(ctx, ins);
                break;

            // --- Memory operations ---
            case MEMORY_SIZE:
                NativeEmitters.emitMemorySize(ctx);
                break;
            case MEMORY_GROW:
                NativeEmitters.emitMemoryGrow(ctx);
                break;
            case MEMORY_COPY:
                NativeEmitters.emitMemoryCopy(ctx);
                break;
            case MEMORY_FILL:
                NativeEmitters.emitMemoryFill(ctx);
                break;
            case MEMORY_INIT:
                NativeEmitters.emitMemoryInit(ctx, ins);
                break;
            case DATA_DROP:
                NativeEmitters.emitDataDrop(ctx, ins);
                break;

            // --- Misc ---
            case NOP:
                break;
            case DROP:
                valueStack.pop();
                break;
            case UNREACHABLE:
                NativeEmitters.emitUnreachable(ctx);
                controlStack.peek().unreachable = true;
                break;

            // --- f32 Arithmetic ---
            case F32_ADD:
                NativeEmitters.emitFloatBinaryOp(ctx, 0);
                break;
            case F32_SUB:
                NativeEmitters.emitFloatBinaryOp(ctx, 1);
                break;
            case F32_MUL:
                NativeEmitters.emitFloatBinaryOp(ctx, 2);
                break;
            case F32_DIV:
                NativeEmitters.emitFloatBinaryOp(ctx, 3);
                break;
            case F32_MIN:
                NativeEmitters.emitFloatBinaryOp(ctx, 4);
                break;
            case F32_MAX:
                NativeEmitters.emitFloatBinaryOp(ctx, 5);
                break;
            case F32_COPYSIGN:
                NativeEmitters.emitFloatBinaryOp(ctx, 6);
                break;
            case F32_ABS:
                NativeEmitters.emitFloatUnaryOp(ctx, 0);
                break;
            case F32_NEG:
                NativeEmitters.emitFloatUnaryOp(ctx, 1);
                break;
            case F32_CEIL:
                NativeEmitters.emitFloatUnaryOp(ctx, 2);
                break;
            case F32_FLOOR:
                NativeEmitters.emitFloatUnaryOp(ctx, 3);
                break;
            case F32_TRUNC:
                NativeEmitters.emitFloatUnaryOp(ctx, 4);
                break;
            case F32_NEAREST:
                NativeEmitters.emitFloatUnaryOp(ctx, 5);
                break;
            case F32_SQRT:
                NativeEmitters.emitFloatUnaryOp(ctx, 6);
                break;

            // --- f32 Comparisons ---
            case F32_EQ:
                NativeEmitters.emitFcmp(ctx, 0);
                break;
            case F32_NE:
                NativeEmitters.emitFcmp(ctx, 1);
                break;
            case F32_LT:
                NativeEmitters.emitFcmp(ctx, 2);
                break;
            case F32_GT:
                NativeEmitters.emitFcmp(ctx, 3);
                break;
            case F32_LE:
                NativeEmitters.emitFcmp(ctx, 4);
                break;
            case F32_GE:
                NativeEmitters.emitFcmp(ctx, 5);
                break;

            // --- f64 Arithmetic ---
            case F64_ADD:
                NativeEmitters.emitFloatBinaryOp(ctx, 0);
                break;
            case F64_SUB:
                NativeEmitters.emitFloatBinaryOp(ctx, 1);
                break;
            case F64_MUL:
                NativeEmitters.emitFloatBinaryOp(ctx, 2);
                break;
            case F64_DIV:
                NativeEmitters.emitFloatBinaryOp(ctx, 3);
                break;
            case F64_MIN:
                NativeEmitters.emitFloatBinaryOp(ctx, 4);
                break;
            case F64_MAX:
                NativeEmitters.emitFloatBinaryOp(ctx, 5);
                break;
            case F64_COPYSIGN:
                NativeEmitters.emitFloatBinaryOp(ctx, 6);
                break;
            case F64_ABS:
                NativeEmitters.emitFloatUnaryOp(ctx, 0);
                break;
            case F64_NEG:
                NativeEmitters.emitFloatUnaryOp(ctx, 1);
                break;
            case F64_CEIL:
                NativeEmitters.emitFloatUnaryOp(ctx, 2);
                break;
            case F64_FLOOR:
                NativeEmitters.emitFloatUnaryOp(ctx, 3);
                break;
            case F64_TRUNC:
                NativeEmitters.emitFloatUnaryOp(ctx, 4);
                break;
            case F64_NEAREST:
                NativeEmitters.emitFloatUnaryOp(ctx, 5);
                break;
            case F64_SQRT:
                NativeEmitters.emitFloatUnaryOp(ctx, 6);
                break;

            // --- f64 Comparisons ---
            case F64_EQ:
                NativeEmitters.emitFcmp(ctx, 0);
                break;
            case F64_NE:
                NativeEmitters.emitFcmp(ctx, 1);
                break;
            case F64_LT:
                NativeEmitters.emitFcmp(ctx, 2);
                break;
            case F64_GT:
                NativeEmitters.emitFcmp(ctx, 3);
                break;
            case F64_LE:
                NativeEmitters.emitFcmp(ctx, 4);
                break;
            case F64_GE:
                NativeEmitters.emitFcmp(ctx, 5);
                break;

            // --- Conversions ---
            case I32_TRUNC_F32_S:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I32, CraneliftBridge.TYPE_F32, true);
                break;
            case I32_TRUNC_F32_U:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I32, CraneliftBridge.TYPE_F32, false);
                break;
            case I32_TRUNC_F64_S:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I32, CraneliftBridge.TYPE_F64, true);
                break;
            case I32_TRUNC_F64_U:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I32, CraneliftBridge.TYPE_F64, false);
                break;
            case I64_TRUNC_F32_S:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I64, CraneliftBridge.TYPE_F32, true);
                break;
            case I64_TRUNC_F32_U:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I64, CraneliftBridge.TYPE_F32, false);
                break;
            case I64_TRUNC_F64_S:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I64, CraneliftBridge.TYPE_F64, true);
                break;
            case I64_TRUNC_F64_U:
                NativeEmitters.emitSafeTrunc(
                        ctx, CraneliftBridge.TYPE_I64, CraneliftBridge.TYPE_F64, false);
                break;

            case I32_TRUNC_SAT_F32_S:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I32, true);
                break;
            case I32_TRUNC_SAT_F32_U:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I32, false);
                break;
            case I32_TRUNC_SAT_F64_S:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I32, true);
                break;
            case I32_TRUNC_SAT_F64_U:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I32, false);
                break;
            case I64_TRUNC_SAT_F32_S:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I64, true);
                break;
            case I64_TRUNC_SAT_F32_U:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I64, false);
                break;
            case I64_TRUNC_SAT_F64_S:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I64, true);
                break;
            case I64_TRUNC_SAT_F64_U:
                NativeEmitters.emitTruncSat(ctx, CraneliftBridge.TYPE_I64, false);
                break;

            case F32_CONVERT_I32_S:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F32, true);
                break;
            case F32_CONVERT_I32_U:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F32, false);
                break;
            case F32_CONVERT_I64_S:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F32, true);
                break;
            case F32_CONVERT_I64_U:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F32, false);
                break;
            case F64_CONVERT_I32_S:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F64, true);
                break;
            case F64_CONVERT_I32_U:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F64, false);
                break;
            case F64_CONVERT_I64_S:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F64, true);
                break;
            case F64_CONVERT_I64_U:
                NativeEmitters.emitConvertFloat(ctx, CraneliftBridge.TYPE_F64, false);
                break;

            case F64_PROMOTE_F32:
                NativeEmitters.emitFpromote(ctx);
                break;
            case F32_DEMOTE_F64:
                NativeEmitters.emitFdemote(ctx);
                break;

            case F32_REINTERPRET_I32:
                NativeEmitters.emitBitcastI32ToF32(ctx);
                break;
            case I32_REINTERPRET_F32:
                NativeEmitters.emitBitcastF32ToI32(ctx);
                break;
            case F64_REINTERPRET_I64:
                NativeEmitters.emitBitcastI64ToF64(ctx);
                break;
            case I64_REINTERPRET_F64:
                NativeEmitters.emitBitcastF64ToI64(ctx);
                break;

            // --- Calls ---
            case CALL:
                NativeEmitters.emitCall(ctx, ins);
                break;
            case CALL_INDIRECT:
                NativeEmitters.emitCallIndirect(ctx, ins);
                break;
            case RETURN_CALL:
                NativeEmitters.emitReturnCall(ctx, ins);
                controlStack.peek().unreachable = true;
                break;
            case RETURN_CALL_INDIRECT:
                NativeEmitters.emitReturnCallIndirect(ctx, ins);
                controlStack.peek().unreachable = true;
                break;

            // --- Control flow (stays in orchestrator) ---
            case BLOCK:
                emitBlock(ctx, ins, controlStack);
                break;
            case LOOP:
                emitLoop(ctx, ins, controlStack);
                break;
            case IF:
                emitIf(ctx, ins, controlStack);
                break;
            case ELSE:
                emitElse(ctx, controlStack);
                break;
            // END is handled in the main loop, not here
            case BR:
                emitBr(ctx, ins, controlStack);
                break;
            case BR_IF:
                emitBrIf(ctx, ins, controlStack);
                break;
            case BR_TABLE:
                emitBrTable(ctx, ins, controlStack);
                break;
            case RETURN:
                emitReturn(ctx, controlStack);
                break;

            // --- Table operations ---
            case TABLE_GET:
                NativeEmitters.emitTableGet(ctx, ins);
                break;
            case TABLE_SET:
                NativeEmitters.emitTableSet(ctx, ins);
                break;
            case TABLE_SIZE:
                NativeEmitters.emitTableSize(ctx, ins);
                break;
            case TABLE_GROW:
                NativeEmitters.emitTableGrow(ctx, ins);
                break;
            case TABLE_FILL:
                NativeEmitters.emitTableFill(ctx, ins);
                break;
            case TABLE_COPY:
                NativeEmitters.emitTableCopy(ctx, ins);
                break;
            case TABLE_INIT:
                NativeEmitters.emitTableInit(ctx, ins);
                break;
            case ELEM_DROP:
                NativeEmitters.emitElemDrop(ctx, ins);
                break;

            // --- Atomic loads ---
            case I32_ATOMIC_LOAD:
                NativeEmitters.emitAtomicLoad(ctx, ins, 0);
                break;
            case I64_ATOMIC_LOAD:
                NativeEmitters.emitAtomicLoad(ctx, ins, 1);
                break;
            case I32_ATOMIC_LOAD8_U:
                NativeEmitters.emitAtomicLoad(ctx, ins, 2);
                break;
            case I32_ATOMIC_LOAD16_U:
                NativeEmitters.emitAtomicLoad(ctx, ins, 3);
                break;
            case I64_ATOMIC_LOAD8_U:
                NativeEmitters.emitAtomicLoad(ctx, ins, 4);
                break;
            case I64_ATOMIC_LOAD16_U:
                NativeEmitters.emitAtomicLoad(ctx, ins, 5);
                break;
            case I64_ATOMIC_LOAD32_U:
                NativeEmitters.emitAtomicLoad(ctx, ins, 6);
                break;

            // --- Atomic stores ---
            case I32_ATOMIC_STORE:
                NativeEmitters.emitAtomicStore(ctx, ins, 0);
                break;
            case I64_ATOMIC_STORE:
                NativeEmitters.emitAtomicStore(ctx, ins, 1);
                break;
            case I32_ATOMIC_STORE8:
                NativeEmitters.emitAtomicStore(ctx, ins, 2);
                break;
            case I32_ATOMIC_STORE16:
                NativeEmitters.emitAtomicStore(ctx, ins, 3);
                break;
            case I64_ATOMIC_STORE8:
                NativeEmitters.emitAtomicStore(ctx, ins, 4);
                break;
            case I64_ATOMIC_STORE16:
                NativeEmitters.emitAtomicStore(ctx, ins, 5);
                break;
            case I64_ATOMIC_STORE32:
                NativeEmitters.emitAtomicStore(ctx, ins, 6);
                break;

            // --- Atomic RMW add ---
            case I32_ATOMIC_RMW_ADD:
                NativeEmitters.emitAtomicRmw(ctx, ins, 0, 0);
                break;
            case I64_ATOMIC_RMW_ADD:
                NativeEmitters.emitAtomicRmw(ctx, ins, 1, 0);
                break;
            case I32_ATOMIC_RMW8_ADD_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 2, 0);
                break;
            case I32_ATOMIC_RMW16_ADD_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 3, 0);
                break;
            case I64_ATOMIC_RMW8_ADD_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 4, 0);
                break;
            case I64_ATOMIC_RMW16_ADD_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 5, 0);
                break;
            case I64_ATOMIC_RMW32_ADD_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 6, 0);
                break;

            // --- Atomic RMW sub ---
            case I32_ATOMIC_RMW_SUB:
                NativeEmitters.emitAtomicRmw(ctx, ins, 0, 1);
                break;
            case I64_ATOMIC_RMW_SUB:
                NativeEmitters.emitAtomicRmw(ctx, ins, 1, 1);
                break;
            case I32_ATOMIC_RMW8_SUB_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 2, 1);
                break;
            case I32_ATOMIC_RMW16_SUB_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 3, 1);
                break;
            case I64_ATOMIC_RMW8_SUB_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 4, 1);
                break;
            case I64_ATOMIC_RMW16_SUB_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 5, 1);
                break;
            case I64_ATOMIC_RMW32_SUB_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 6, 1);
                break;

            // --- Atomic RMW and ---
            case I32_ATOMIC_RMW_AND:
                NativeEmitters.emitAtomicRmw(ctx, ins, 0, 2);
                break;
            case I64_ATOMIC_RMW_AND:
                NativeEmitters.emitAtomicRmw(ctx, ins, 1, 2);
                break;
            case I32_ATOMIC_RMW8_AND_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 2, 2);
                break;
            case I32_ATOMIC_RMW16_AND_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 3, 2);
                break;
            case I64_ATOMIC_RMW8_AND_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 4, 2);
                break;
            case I64_ATOMIC_RMW16_AND_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 5, 2);
                break;
            case I64_ATOMIC_RMW32_AND_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 6, 2);
                break;

            // --- Atomic RMW or ---
            case I32_ATOMIC_RMW_OR:
                NativeEmitters.emitAtomicRmw(ctx, ins, 0, 3);
                break;
            case I64_ATOMIC_RMW_OR:
                NativeEmitters.emitAtomicRmw(ctx, ins, 1, 3);
                break;
            case I32_ATOMIC_RMW8_OR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 2, 3);
                break;
            case I32_ATOMIC_RMW16_OR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 3, 3);
                break;
            case I64_ATOMIC_RMW8_OR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 4, 3);
                break;
            case I64_ATOMIC_RMW16_OR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 5, 3);
                break;
            case I64_ATOMIC_RMW32_OR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 6, 3);
                break;

            // --- Atomic RMW xor ---
            case I32_ATOMIC_RMW_XOR:
                NativeEmitters.emitAtomicRmw(ctx, ins, 0, 4);
                break;
            case I64_ATOMIC_RMW_XOR:
                NativeEmitters.emitAtomicRmw(ctx, ins, 1, 4);
                break;
            case I32_ATOMIC_RMW8_XOR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 2, 4);
                break;
            case I32_ATOMIC_RMW16_XOR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 3, 4);
                break;
            case I64_ATOMIC_RMW8_XOR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 4, 4);
                break;
            case I64_ATOMIC_RMW16_XOR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 5, 4);
                break;
            case I64_ATOMIC_RMW32_XOR_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 6, 4);
                break;

            // --- Atomic RMW xchg ---
            case I32_ATOMIC_RMW_XCHG:
                NativeEmitters.emitAtomicRmw(ctx, ins, 0, 5);
                break;
            case I64_ATOMIC_RMW_XCHG:
                NativeEmitters.emitAtomicRmw(ctx, ins, 1, 5);
                break;
            case I32_ATOMIC_RMW8_XCHG_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 2, 5);
                break;
            case I32_ATOMIC_RMW16_XCHG_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 3, 5);
                break;
            case I64_ATOMIC_RMW8_XCHG_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 4, 5);
                break;
            case I64_ATOMIC_RMW16_XCHG_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 5, 5);
                break;
            case I64_ATOMIC_RMW32_XCHG_U:
                NativeEmitters.emitAtomicRmw(ctx, ins, 6, 5);
                break;

            // --- Atomic cmpxchg ---
            case I32_ATOMIC_RMW_CMPXCHG:
                NativeEmitters.emitAtomicCmpxchg(ctx, ins, 0);
                break;
            case I64_ATOMIC_RMW_CMPXCHG:
                NativeEmitters.emitAtomicCmpxchg(ctx, ins, 1);
                break;
            case I32_ATOMIC_RMW8_CMPXCHG_U:
                NativeEmitters.emitAtomicCmpxchg(ctx, ins, 2);
                break;
            case I32_ATOMIC_RMW16_CMPXCHG_U:
                NativeEmitters.emitAtomicCmpxchg(ctx, ins, 3);
                break;
            case I64_ATOMIC_RMW8_CMPXCHG_U:
                NativeEmitters.emitAtomicCmpxchg(ctx, ins, 4);
                break;
            case I64_ATOMIC_RMW16_CMPXCHG_U:
                NativeEmitters.emitAtomicCmpxchg(ctx, ins, 5);
                break;
            case I64_ATOMIC_RMW32_CMPXCHG_U:
                NativeEmitters.emitAtomicCmpxchg(ctx, ins, 6);
                break;

            // --- Atomic wait/notify/fence ---
            case MEM_ATOMIC_WAIT32:
                NativeEmitters.emitAtomicWait32(ctx, ins);
                break;
            case MEM_ATOMIC_WAIT64:
                NativeEmitters.emitAtomicWait64(ctx, ins);
                break;
            case MEM_ATOMIC_NOTIFY:
                NativeEmitters.emitAtomicNotify(ctx, ins);
                break;
            case ATOMIC_FENCE:
                NativeEmitters.emitAtomicFence(ctx);
                break;

            // --- Reference types ---
            case REF_NULL:
                NativeEmitters.emitRefNull(ctx);
                break;
            case REF_IS_NULL:
                NativeEmitters.emitRefIsNull(ctx);
                break;
            case REF_FUNC:
                NativeEmitters.emitRefFunc(ctx, ins);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Opcode not yet supported: " + ins.opcode());
        }
    }

    // --- Control flow handlers ---

    private void emitBlock(
            EmitContext ctx, AnnotatedInstruction ins, Deque<ControlFrame> controlStack) {
        FunctionType bt = decodeBlockType(ins);
        int mergeBlock = bridge.exports().createBlock();
        int[] mergeParamIds = appendBlockParams(mergeBlock, bt.returns());
        int savedHeight = ctx.valueStack.size() - bt.params().size();
        controlStack.push(
                new ControlFrame(
                        ControlFrame.Kind.BLOCK,
                        mergeBlock,
                        -1,
                        -1,
                        mergeParamIds,
                        bt,
                        savedHeight));
    }

    private void emitLoop(
            EmitContext ctx, AnnotatedInstruction ins, Deque<ControlFrame> controlStack) {
        FunctionType bt = decodeBlockType(ins);
        int loopHeader = bridge.exports().createBlock();
        int mergeBlock = bridge.exports().createBlock();
        int[] mergeParamIds = appendBlockParams(mergeBlock, bt.returns());
        int[] loopParamIds = appendBlockParams(loopHeader, bt.params());
        int savedHeight = ctx.valueStack.size() - bt.params().size();
        emitJumpToBlock(loopHeader, bt.params().size(), ctx.valueStack);
        bridge.exports().switchToBlock(loopHeader);
        for (int pid : loopParamIds) {
            ctx.valueStack.push(pid);
        }

        // --- Interrupt check at loop header ---
        {
            int flag =
                    bridge.exports()
                            .emitLoadI64(
                                    bridge.exports().useVar(ctx.ctxPtrVar),
                                    bridge.exports().emitIconst32(0),
                                    CtxBuffer.INTERRUPT_FLAG);
            int zeroI64 = bridge.exports().emitIconst64(0, 0);
            int interrupted = bridge.exports().emitIcmp(1, flag, zeroI64); // NE
            int trapBlock = bridge.exports().createBlock();
            int okBlock = bridge.exports().createBlock();
            bridge.exports().emitBrif(interrupted, trapBlock, okBlock);

            NativeEmitters.fillTrapBlock(ctx, trapBlock, CtxBuffer.TRAP_INTERRUPTED);

            bridge.exports().switchToBlock(okBlock);
        }

        controlStack.push(
                new ControlFrame(
                        ControlFrame.Kind.LOOP,
                        mergeBlock,
                        loopHeader,
                        -1,
                        mergeParamIds,
                        bt,
                        savedHeight));
    }

    private void emitIf(
            EmitContext ctx, AnnotatedInstruction ins, Deque<ControlFrame> controlStack) {
        FunctionType bt = decodeBlockType(ins);
        int condition = ctx.valueStack.pop();
        int thenBlock = bridge.exports().createBlock();
        int elseBlock = bridge.exports().createBlock();
        int mergeBlock = bridge.exports().createBlock();
        int[] mergeParamIds = appendBlockParams(mergeBlock, bt.returns());
        int savedHeight = ctx.valueStack.size() - bt.params().size();

        int[] elseParamIds = null;
        if (bt.params().isEmpty()) {
            bridge.exports().emitBrif(condition, thenBlock, elseBlock);
            bridge.exports().switchToBlock(thenBlock);
            bridge.exports().sealBlock(thenBlock);
        } else {
            // Block has params — both branches need them.
            // brif only passes args to the true target, so we use a
            // fallthrough block for the false path.
            int[] thenParamIds = appendBlockParams(thenBlock, bt.params());
            elseParamIds = appendBlockParams(elseBlock, bt.params());

            int paramCount = bt.params().size();
            int[] paramVals = new int[paramCount];
            for (int i = paramCount - 1; i >= 0; i--) {
                paramVals[i] = ctx.valueStack.pop();
            }

            // brif → thenBlock (with args) / fallthroughBlock (no args)
            int fallthroughBlock = bridge.exports().createBlock();
            for (int pv : paramVals) {
                bridge.exports().pushCallArg(pv);
            }
            bridge.exports().emitBrifWithJumpArgs(condition, thenBlock, fallthroughBlock);

            // Fallthrough → elseBlock (with args)
            bridge.exports().switchToBlock(fallthroughBlock);
            if (paramCount == 1) {
                bridge.exports().emitJumpWithArg(elseBlock, paramVals[0]);
            } else {
                for (int pv : paramVals) {
                    bridge.exports().pushCallArg(pv);
                }
                bridge.exports().emitJumpWithArgs(elseBlock);
            }

            // Switch to then block, push its block params onto value stack
            bridge.exports().switchToBlock(thenBlock);
            bridge.exports().sealBlock(thenBlock);
            for (int pid : thenParamIds) {
                ctx.valueStack.push(pid);
            }
        }

        var frame =
                new ControlFrame(
                        ControlFrame.Kind.IF,
                        mergeBlock,
                        -1,
                        elseBlock,
                        mergeParamIds,
                        bt,
                        savedHeight);
        frame.elseParamIds = elseParamIds;
        controlStack.push(frame);
    }

    private void emitElse(EmitContext ctx, Deque<ControlFrame> controlStack) {
        ControlFrame frame = controlStack.peek();
        if (frame.mergeBlock < 0) {
            frame.hasElse = true;
            return;
        }
        if (!frame.unreachable) {
            emitJumpToBlock(frame.mergeBlock, frame.blockType.returns().size(), ctx.valueStack);
        }
        ctx.valueStack.trimTo(frame.stackHeight);
        bridge.exports().switchToBlock(frame.elseBlock);
        bridge.exports().sealBlock(frame.elseBlock);

        // Push else block's param IDs for the else branch
        if (frame.elseParamIds != null) {
            for (int pid : frame.elseParamIds) {
                ctx.valueStack.push(pid);
            }
        }

        frame.hasElse = true;
        frame.unreachable = false;
    }

    private void emitEnd(EmitContext ctx, Deque<ControlFrame> controlStack) {
        ControlFrame frame = controlStack.pop();
        var valueStack = ctx.valueStack;
        boolean isDummy = frame.mergeBlock < 0 && frame.kind != ControlFrame.Kind.FUNCTION;
        boolean dead = frame.unreachable;

        switch (frame.kind) {
            case FUNCTION:
                if (!dead) {
                    emitFuncReturn(ctx, frame.blockType);
                }
                // If dead, the block is already terminated (by BR/RETURN at
                // function level). No return needed — inner block ENDs switch
                // to merge blocks and reset unreachable, so they go through
                // the !dead path above.

                break;

            case BLOCK:
            case LOOP:
                if (isDummy) {
                    break;
                }
                if (!dead) {
                    emitJumpToBlock(frame.mergeBlock, frame.blockType.returns().size(), valueStack);
                } else if (frame.mergeParamIds.length > 0) {
                    emitDeadPredecessor(frame.mergeBlock, frame.blockType.returns());
                }
                bridge.exports().switchToBlock(frame.mergeBlock);
                bridge.exports().sealBlock(frame.mergeBlock);
                // Always use trimTo + push: merge block params are the canonical
                // values regardless of whether the block was reachable.
                valueStack.trimTo(frame.stackHeight);
                for (int pid : frame.mergeParamIds) {
                    valueStack.push(pid);
                }

                break;

            case IF:
                if (isDummy) {
                    break;
                }
                if (!frame.hasElse) {
                    if (!dead) {
                        emitJumpToBlock(
                                frame.mergeBlock, frame.blockType.returns().size(), valueStack);
                    }
                    bridge.exports().switchToBlock(frame.elseBlock);
                    bridge.exports().sealBlock(frame.elseBlock);
                    // Implicit else: pass block params through to merge.
                    // For IF without ELSE, params == returns per Wasm spec.
                    if (frame.elseParamIds != null && frame.elseParamIds.length > 0) {
                        // Use else block's own param values
                        if (frame.elseParamIds.length == 1) {
                            bridge.exports()
                                    .emitJumpWithArg(frame.mergeBlock, frame.elseParamIds[0]);
                        } else {
                            for (int pid : frame.elseParamIds) {
                                bridge.exports().pushCallArg(pid);
                            }
                            bridge.exports().emitJumpWithArgs(frame.mergeBlock);
                        }
                    } else if (frame.blockType.returns().isEmpty()) {
                        bridge.exports().emitJump(frame.mergeBlock);
                    } else {
                        // No else params (paramless IF) — use dummy zeros
                        for (ValType t : frame.blockType.returns()) {
                            bridge.exports().pushCallArg(emitZero(t));
                        }
                        bridge.exports().emitJumpWithArgs(frame.mergeBlock);
                    }
                } else {
                    if (!dead) {
                        emitJumpToBlock(
                                frame.mergeBlock, frame.blockType.returns().size(), valueStack);
                    } else if (frame.mergeParamIds.length > 0) {
                        emitDeadPredecessor(frame.mergeBlock, frame.blockType.returns());
                    }
                }
                bridge.exports().switchToBlock(frame.mergeBlock);
                bridge.exports().sealBlock(frame.mergeBlock);
                valueStack.trimTo(frame.stackHeight);
                for (int pid : frame.mergeParamIds) {
                    valueStack.push(pid);
                }

                break;
        }
        if (!controlStack.isEmpty() && !isDummy) {
            controlStack.peek().unreachable = false;
        }
    }

    private void emitFuncReturn(EmitContext ctx, FunctionType funcType) {
        int retCount = funcType.returns().size();
        var valueStack = ctx.valueStack;
        if (retCount == 0) {
            bridge.exports().emitReturnVoid();
        } else if (!ctx.multiReturn && retCount == 1 && !valueStack.isEmpty()) {
            bridge.exports().emitReturn(valueStack.pop());
        } else if (retCount >= 1 && valueStack.size() >= retCount) {
            int[] retVals = new int[retCount];
            for (int ri = retCount - 1; ri >= 0; ri--) {
                retVals[ri] = valueStack.pop();
            }
            if (ctx.multiReturn) {
                ctx.emitWriteReturnsToArgsBuffer(funcType.returns(), retVals);
                bridge.exports().emitReturn(bridge.exports().emitIconst64(0, 0));
            } else {
                bridge.exports().emitReturn(retVals[0]);
            }
        } else {
            ctx.emitReturnForFuncType();
        }
    }

    private void emitReturnWithArgs(EmitContext ctx, int[] args, int argCount) {
        if (argCount == 0) {
            bridge.exports().emitReturnVoid();
        } else if (!ctx.multiReturn && argCount == 1) {
            bridge.exports().emitReturn(args[0]);
        } else if (ctx.multiReturn) {
            ctx.emitWriteReturnsToArgsBuffer(ctx.funcType.returns(), args);
            bridge.exports().emitReturn(bridge.exports().emitIconst64(0, 0));
        } else {
            bridge.exports().emitReturn(args[0]);
        }
    }

    private void emitBrToFunction(EmitContext ctx, ControlFrame funcFrame) {
        int retCount = funcFrame.blockType.returns().size();
        if (retCount == 0) {
            bridge.exports().emitReturnVoid();
        } else if (!ctx.multiReturn && retCount == 1) {
            bridge.exports().emitReturn(ctx.valueStack.pop());
        } else {
            int[] retVals = new int[retCount];
            for (int i = retCount - 1; i >= 0; i--) {
                retVals[i] = ctx.valueStack.pop();
            }
            if (ctx.multiReturn) {
                ctx.emitWriteReturnsToArgsBuffer(funcFrame.blockType.returns(), retVals);
                bridge.exports().emitReturn(bridge.exports().emitIconst64(0, 0));
            } else {
                bridge.exports().emitReturn(retVals[0]);
            }
        }
    }

    private void emitBr(
            EmitContext ctx, AnnotatedInstruction ins, Deque<ControlFrame> controlStack) {
        int depth = (int) ins.operands()[0];
        ControlFrame target = getControlFrame(controlStack, depth);
        if (target.kind == ControlFrame.Kind.FUNCTION) {
            emitBrToFunction(ctx, target);
        } else {
            int brTarget = target.branchTarget();
            int argCount = target.branchArgCount();
            emitJumpToBlock(brTarget, argCount, ctx.valueStack);
        }
        controlStack.peek().unreachable = true;
    }

    private void emitBrIf(
            EmitContext ctx, AnnotatedInstruction ins, Deque<ControlFrame> controlStack) {
        int depth = (int) ins.operands()[0];
        int condition = ctx.valueStack.pop();
        ControlFrame target = getControlFrame(controlStack, depth);

        if (target.kind == ControlFrame.Kind.FUNCTION) {
            // BR_IF targeting function = conditional return
            int returnBlock = bridge.exports().createBlock();
            int fallthroughBlock = bridge.exports().createBlock();
            int argCount = target.branchArgCount();

            if (argCount > 0) {
                int[] args = new int[argCount];
                for (int i = argCount - 1; i >= 0; i--) {
                    args[i] = ctx.valueStack.pop();
                }
                // Add block params to returnBlock so brif can pass values
                int[] returnParams = new int[argCount];
                var returns = target.blockType.returns();
                for (int i = 0; i < argCount; i++) {
                    returnParams[i] =
                            bridge.exports()
                                    .appendBlockParam(
                                            returnBlock,
                                            EmitContext.valTypeToBridgeType(returns.get(i)));
                }
                for (int a : args) {
                    bridge.exports().pushCallArg(a);
                }
                bridge.exports().emitBrifWithJumpArgs(condition, returnBlock, fallthroughBlock);
                // Push args back for fallthrough
                for (int a : args) {
                    ctx.valueStack.push(a);
                }
                // Return block: emit return with block params (not original args)
                bridge.exports().switchToBlock(returnBlock);
                emitReturnWithArgs(ctx, returnParams, argCount);
            } else {
                bridge.exports().emitBrif(condition, returnBlock, fallthroughBlock);
                bridge.exports().switchToBlock(returnBlock);
                bridge.exports().emitReturnVoid();
            }
            bridge.exports().switchToBlock(fallthroughBlock);
            return;
        }

        int brTarget = target.branchTarget();
        int fallthroughBlock = bridge.exports().createBlock();
        int argCount = target.branchArgCount();

        if (argCount > 0) {
            int[] args = new int[argCount];
            for (int i = argCount - 1; i >= 0; i--) {
                args[i] = ctx.valueStack.pop();
            }
            for (int a : args) {
                bridge.exports().pushCallArg(a);
            }
            bridge.exports().emitBrifWithJumpArgs(condition, brTarget, fallthroughBlock);
            for (int a : args) {
                ctx.valueStack.push(a);
            }
        } else {
            bridge.exports().emitBrif(condition, brTarget, fallthroughBlock);
        }
        bridge.exports().switchToBlock(fallthroughBlock);
    }

    private void emitBrTable(
            EmitContext ctx, AnnotatedInstruction ins, Deque<ControlFrame> controlStack) {
        int index = ctx.valueStack.pop();
        int defaultIdx = ins.operandCount() - 1;
        int defaultDepth = (int) ins.operand(defaultIdx);

        ControlFrame defaultTarget = getControlFrame(controlStack, defaultDepth);
        int argCount = defaultTarget.branchArgCount();
        int[] brArgs = new int[argCount];
        for (int i = argCount - 1; i >= 0; i--) {
            brArgs[i] = ctx.valueStack.pop();
        }

        // Build jump table targets with edge splitting.
        // Cranelift's br_table doesn't support block arguments, and FUNCTION
        // targets need a return instead of a jump — so we create intermediate
        // blocks for any target that needs special handling.
        int[] intermediates = new int[defaultIdx];
        boolean[] needsIntermediate = new boolean[defaultIdx];
        for (int i = 0; i < defaultIdx; i++) {
            int depth = (int) ins.operand(i);
            ControlFrame target = getControlFrame(controlStack, depth);
            if (argCount > 0 || target.kind == ControlFrame.Kind.FUNCTION) {
                intermediates[i] = bridge.exports().createBlock();
                needsIntermediate[i] = true;
                bridge.exports().pushBrTableTarget(intermediates[i]);
            } else {
                bridge.exports().pushBrTableTarget(target.branchTarget());
            }
        }
        boolean defaultNeedsIntermediate =
                argCount > 0 || defaultTarget.kind == ControlFrame.Kind.FUNCTION;
        int defaultJtTarget;
        if (defaultNeedsIntermediate) {
            defaultJtTarget = bridge.exports().createBlock();
        } else {
            defaultJtTarget = defaultTarget.branchTarget();
        }
        bridge.exports().emitBrTable(index, defaultJtTarget);

        // Emit intermediate blocks
        for (int i = 0; i < defaultIdx; i++) {
            if (!needsIntermediate[i]) {
                continue;
            }
            bridge.exports().switchToBlock(intermediates[i]);
            bridge.exports().sealBlock(intermediates[i]);
            int depth = (int) ins.operand(i);
            ControlFrame target = getControlFrame(controlStack, depth);
            if (target.kind == ControlFrame.Kind.FUNCTION) {
                emitReturnWithArgs(ctx, brArgs, argCount);
            } else if (argCount == 0) {
                bridge.exports().emitJump(target.branchTarget());
            } else {
                emitJumpWithArgs(target.branchTarget(), brArgs, argCount);
            }
        }

        if (defaultNeedsIntermediate) {
            bridge.exports().switchToBlock(defaultJtTarget);
            bridge.exports().sealBlock(defaultJtTarget);
            if (defaultTarget.kind == ControlFrame.Kind.FUNCTION) {
                emitReturnWithArgs(ctx, brArgs, argCount);
            } else if (argCount == 0) {
                bridge.exports().emitJump(defaultTarget.branchTarget());
            } else {
                emitJumpWithArgs(defaultTarget.branchTarget(), brArgs, argCount);
            }
        }

        controlStack.peek().unreachable = true;
    }

    private void emitJumpWithArgs(int blockId, int[] args, int argCount) {
        if (argCount == 1) {
            bridge.exports().emitJumpWithArg(blockId, args[0]);
        } else {
            for (int a : args) {
                bridge.exports().pushCallArg(a);
            }
            bridge.exports().emitJumpWithArgs(blockId);
        }
    }

    private void emitReturn(EmitContext ctx, Deque<ControlFrame> controlStack) {
        ControlFrame funcFrame = null;
        for (ControlFrame f : controlStack) {
            funcFrame = f;
        }
        if (funcFrame != null) {
            emitBrToFunction(ctx, funcFrame);
        } else {
            bridge.exports().emitReturnVoid();
        }
        controlStack.peek().unreachable = true;
    }
}
