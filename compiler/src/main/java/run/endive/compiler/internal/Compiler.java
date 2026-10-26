package run.endive.compiler.internal;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.toSet;
import static org.objectweb.asm.Type.INT_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;
import static org.objectweb.asm.Type.VOID_TYPE;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getInternalName;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getType;
import static org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE;
import static run.endive.compiler.internal.CompilerUtil.asmType;
import static run.endive.compiler.internal.CompilerUtil.callDispatchMethodName;
import static run.endive.compiler.internal.CompilerUtil.callIndirectMethodName;
import static run.endive.compiler.internal.CompilerUtil.callIndirectMethodType;
import static run.endive.compiler.internal.CompilerUtil.callMethodName;
import static run.endive.compiler.internal.CompilerUtil.callWithRefsDispatchMethodName;
import static run.endive.compiler.internal.CompilerUtil.callWithRefsMethodName;
import static run.endive.compiler.internal.CompilerUtil.classNameForCallIndirect;
import static run.endive.compiler.internal.CompilerUtil.classNameForCallWithRefsDispatch;
import static run.endive.compiler.internal.CompilerUtil.classNameForDispatch;
import static run.endive.compiler.internal.CompilerUtil.defaultValue;
import static run.endive.compiler.internal.CompilerUtil.emitInvokeFunction;
import static run.endive.compiler.internal.CompilerUtil.emitInvokeStatic;
import static run.endive.compiler.internal.CompilerUtil.emitInvokeVirtual;
import static run.endive.compiler.internal.CompilerUtil.emitJvmToLong;
import static run.endive.compiler.internal.CompilerUtil.emitLongToJvm;
import static run.endive.compiler.internal.CompilerUtil.hasTooManyParameters;
import static run.endive.compiler.internal.CompilerUtil.internalClassName;
import static run.endive.compiler.internal.CompilerUtil.jvmParameterTypes;
import static run.endive.compiler.internal.CompilerUtil.jvmReturnType;
import static run.endive.compiler.internal.CompilerUtil.jvmType;
import static run.endive.compiler.internal.CompilerUtil.localType;
import static run.endive.compiler.internal.CompilerUtil.methodNameForFunc;
import static run.endive.compiler.internal.CompilerUtil.methodTypeFor;
import static run.endive.compiler.internal.CompilerUtil.newClassWriter;
import static run.endive.compiler.internal.CompilerUtil.rawMethodTypeFor;
import static run.endive.compiler.internal.CompilerUtil.slotCount;
import static run.endive.compiler.internal.CompilerUtil.valueMethodName;
import static run.endive.compiler.internal.CompilerUtil.valueMethodType;
import static run.endive.compiler.internal.EmitterMap.EMITTERS;
import static run.endive.compiler.internal.ShadedRefs.AOT_INTERPRETER_MACHINE_CALL;
import static run.endive.compiler.internal.ShadedRefs.CALL_HOST_FUNCTION;
import static run.endive.compiler.internal.ShadedRefs.CALL_HOST_FUNCTION_WITH_REFS;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT_CROSS_MODULE;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT_ON_INTERPRETER;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT_ON_INTERPRETER_WITH_REFS;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT_WITH_REFS_CROSS_MODULE;
import static run.endive.compiler.internal.ShadedRefs.CHECK_INTERRUPTION;
import static run.endive.compiler.internal.ShadedRefs.INSTANCE_GET_TYPE;
import static run.endive.compiler.internal.ShadedRefs.INSTANCE_MEMORY;
import static run.endive.compiler.internal.ShadedRefs.INSTANCE_TABLE;
import static run.endive.compiler.internal.ShadedRefs.TABLE_INSTANCE;
import static run.endive.compiler.internal.ShadedRefs.TABLE_REQUIRED_REF;
import static run.endive.compiler.internal.ShadedRefs.THROW_CALL_STACK_EXHAUSTED;
import static run.endive.compiler.internal.ShadedRefs.THROW_INDIRECT_CALL_TYPE_MISMATCH;
import static run.endive.compiler.internal.ShadedRefs.THROW_UNKNOWN_FUNCTION;
import static run.endive.compiler.internal.Shader.createShadedClass;
import static run.endive.compiler.internal.Shader.shadedClassRemapper;

import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.objectweb.asm.ClassTooLargeException;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodTooLargeException;
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
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MethodPrefixer;
import run.endive.runtime.CallResult;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmException;
import run.endive.runtime.internal.CompilerInterpreterMachine;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.FunctionBody;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.OpCode;
import run.endive.wasm.types.ValType;

public final class Compiler {

    public static final String DEFAULT_CLASS_NAME = "run.endive.$gen.CompiledMachine";
    private static final Type LONG_ARRAY_TYPE = Type.getType(long[].class);
    private static final Type INT_ARRAY_TYPE = Type.getType(int[].class);
    private static final String CALL_RESULT_INTERNAL_NAME = Type.getInternalName(CallResult.class);
    private static final String CALL_RESULT_OF_DESCRIPTOR =
            Type.getMethodDescriptor(
                    Type.getType(CallResult.class), LONG_ARRAY_TYPE, Type.getType(Object[].class));
    private static final Type AOT_INTERPRETER_MACHINE_TYPE =
            Type.getType(CompilerInterpreterMachine.class);
    private static final Type INSTANCE_TYPE = Type.getType(Instance.class);

    private static final MethodType CALL_METHOD_TYPE_WITH_REFS =
            methodType(long[].class, Instance.class, Memory.class, long[].class, Object[].class);

    private static final MethodType CALL_WITH_REFS_BRIDGE_METHOD_TYPE =
            methodType(
                    CallResult.class, Instance.class, Memory.class, long[].class, Object[].class);

    private static final MethodType MACHINE_CALL_METHOD_TYPE_WITH_REFS =
            methodType(
                    long[].class,
                    Instance.class,
                    Memory.class,
                    int.class,
                    long[].class,
                    Object[].class);

    private static final MethodType MACHINE_CALL_WITH_REFS_METHOD_TYPE =
            methodType(
                    CallResult.class,
                    Instance.class,
                    Memory.class,
                    int.class,
                    long[].class,
                    Object[].class);

    // C2 JIT's HugeMethodLimit (default 8KB) — methods exceeding this get degraded optimization.
    // Dispatch chunks are sized to stay under this limit for full C2 compilation.
    private static final int HUGE_METHOD_LIMIT = Integer.getInteger("endive.hugeMethodLimit", 8000);
    // Estimated upper bound: tableswitch offset (4) + label + invokestatic (~5) + areturn (1) +
    // overhead
    private static final int ESTIMATED_BYTES_PER_DISPATCH_ENTRY = 40;
    private static final int MAX_DISPATCH_METHODS =
            Integer.highestOneBit(
                    HUGE_METHOD_LIMIT / ESTIMATED_BYTES_PER_DISPATCH_ENTRY); // must be power of two
    private static final int MAX_CALL_INDIRECT_METHODS = 1024; // must be power of two
    // 1024*12 was empirically determined to work for the 50K small wasm functions.
    // So lets start there and halve it until we find a size that works.
    // This should give us the biggest class size possible.
    private static final int DEFAULT_MAX_FUNCTIONS_PER_CLASS = 1024 * 12;

    // ASM's exception-table bookkeeping (Handler.removeRange, invoked while the method
    // writer finalizes code and computes stack-map frames) is a recursive linked-list
    // walk whose cost -- and required JVM thread-stack depth -- scales with the number
    // of handlers registered in a single method. A Wasm function with a very large
    // number of try_table blocks (e.g. from aggressive inlining of exception-heavy code
    // at high optimization levels) can overflow the stack, or simply take many seconds,
    // while ASM tries to finish writing it -- well before the size-based
    // MethodTooLargeException fallback below ever gets a chance to trigger, since that's
    // only detected once the method has already been fully written. Detect and route
    // these functions to the interpreter up front instead of attempting the compile.
    private static final int MAX_TRY_TABLE_HANDLERS_PER_FUNCTION =
            Integer.getInteger("endive.maxTryTableHandlersPerFunction", 256);

    // HotSpot's optimizing JIT (C2, or Graal under JVMCI) declines to compile a method
    // whose bytecode exceeds a size threshold at all (DontCompileHugeMethods), leaving
    // it permanently on a much slower tier no matter how hot it runs -- confirmed
    // empirically against a real, deployed module: a 7920-byte method was the largest
    // ever observed to even be *attempted* by the JIT in a multi-minute production
    // session, and several 9000-24000 byte functions from the same module never
    // appeared in the JIT's compilation log at all. Endive's existing chunking only
    // guards against the much larger (65535 byte) hard MethodTooLargeException limit,
    // so a single Wasm function that compiles into one oversized JVM method sails
    // straight through that guard and silently lands on the slow tier forever.
    //
    // This is a diagnostic-only check for now: it measures the actual compiled size of
    // functions large enough to plausibly be at risk and warns, without changing what
    // gets emitted. Splitting such functions into JIT-eligible pieces is a separate,
    // higher-risk follow-up (needs real operand-stack analysis to find safe cut points
    // -- this flags the functions that would need it).
    private static final int LARGE_FUNCTION_INSTRUCTION_THRESHOLD =
            Integer.getInteger("endive.largeFunctionInstructionThreshold", 200);
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
            Math.min(HUGE_METHOD_LIMIT, JVM_METHOD_SIZE_CEILING);

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
    private static final int SWITCH_SPLIT_MIN_TARGETS =
            Integer.getInteger("endive.switchSplitMinTargets", 8);

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
    private static final boolean TRAMPOLINE_SPLIT_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("endive.trampolineSplit", "true"));

    // Dispatch-loop functions inherently produce roughly one chunk per switch case
    // (every case body is a branch target from the dispatch chunk, so it has to be
    // its own chunk entry). That is fine and even desirable -- it is what a
    // hand-written Java interpreter looks like -- but cap it so a pathological
    // function can't emit an unbounded number of methods into one class.
    private static final int TRAMPOLINE_MAX_CHUNKS =
            Integer.getInteger("endive.trampolineMaxChunks", 4096);

    // Diagnostic only: skip linear chunking so every splittable function exercises
    // the trampoline path. Real workloads reach it rarely (only when linear
    // chunking fails), so this is how the spec suite gets to cover it broadly.
    private static final boolean TRAMPOLINE_SPLIT_FORCED =
            Boolean.getBoolean("endive.forceTrampolineSplit");

    // PC value meaning "this chunk returned normally; the value it returned is the
    // function's real result". Chunks store it on entry, so only an explicit
    // cross-chunk transition ever overwrites it.
    private static final int TRAMPOLINE_PC_DONE = -1;

    private final String className;
    private final WasmModule module;
    private final WasmAnalyzer analyzer;
    private final int functionImports;
    private final InterpreterFallback interpreterFallback;
    private final List<FunctionType> functionTypes;
    private final Supplier<ClassCollector> classCollectorFactory;
    private ClassCollector collector;
    private int maxFunctionsPerClass;
    private final HashSet<Integer> interpretedFunctions;
    private final boolean hasFixedInterpretedFunctionSet;
    private final Map<Integer, int[]> functionChunkBoundaries = new HashMap<>();

    // funcId -> chunk *start* positions (starts[0] is always 0). Distinct from
    // functionChunkBoundaries, which holds inclusive end positions for the linear
    // splitter; keeping the two representations separate avoids any chance of one
    // path reading the other's indices with the wrong convention.
    private final Map<Integer, int[]> trampolineChunkStarts = new HashMap<>();
    private final Set<Integer> callRefTypeIds;
    private final boolean[] tailCallFunctions;
    private final boolean[] tailCallTypes;
    private final boolean moduleHasTailCalls;
    private final boolean moduleHasObjectRefs;
    private final String[] methodNames;
    private boolean useBridgeClasses;
    private IntFunction<String> callIndirectClassResolver;

    private Compiler(
            WasmModule module,
            String className,
            int maxFunctionsPerClass,
            InterpreterFallback interpreterFallback,
            Set<Integer> interpretedFunctions,
            Supplier<ClassCollector> classCollectorFactory,
            MethodPrefixer methodPrefixer) {
        this.className = requireNonNull(className, "className");
        this.module = requireNonNull(module, "module");
        this.analyzer = new WasmAnalyzer(module);
        this.functionImports = module.importSection().count(ExternalType.FUNCTION);
        this.classCollectorFactory = classCollectorFactory;
        this.collector = classCollectorFactory.get();

        this.hasFixedInterpretedFunctionSet =
                interpretedFunctions != null && !interpretedFunctions.isEmpty();
        if (!hasFixedInterpretedFunctionSet) {
            this.interpretedFunctions = new HashSet<>();
            this.interpreterFallback =
                    requireNonNullElse(interpreterFallback, InterpreterFallback.WARN);
        } else if (interpreterFallback != null && interpreterFallback != InterpreterFallback.FAIL) {
            // if we are being given a set of interpreted functions, then any unlisted
            // function needs to trigger a failure.
            throw new IllegalArgumentException(
                    "InterpreterFallback must be set to FAIL if a fixed set of interpreted"
                            + " functions is provided");
        } else {
            this.interpretedFunctions = new HashSet<>(interpretedFunctions);
            this.interpreterFallback = InterpreterFallback.FAIL;
        }
        routeHandlerDenseFunctionsToInterpreter();

        this.functionTypes = analyzer.functionTypes();
        this.callRefTypeIds = collectCallRefTypeIds();
        this.tailCallFunctions = analyzer.tailCallFunctions();
        this.tailCallTypes = analyzer.tailCallTypes();
        this.moduleHasTailCalls = analyzer.hasTailCalls();
        this.moduleHasObjectRefs =
                this.functionTypes.stream()
                        .anyMatch(ft -> ft.hasObjectRefParams() || ft.hasObjectRefReturns());
        this.maxFunctionsPerClass = maxFunctionsPerClass;
        // Resolve every method name up front, so that the prefixer is consulted exactly once per
        // function and definitions and call sites cannot disagree.
        var prefixer = requireNonNullElse(methodPrefixer, MethodPrefixer.defaultPrefixer());
        this.methodNames = new String[this.functionTypes.size()];
        for (int funcId = 0; funcId < this.methodNames.length; funcId++) {
            this.methodNames[funcId] = methodNameForFunc(funcId, prefixer, module);
        }
        // Last: the split planner names its chunk methods after the resolved
        // method names, so every other field has to be in place first.
        planOversizedFunctionSplits();
    }

    private String methodName(int funcId) {
        return methodNames[funcId];
    }

    /**
     * Returns the id of the WASM function compiled into {@code methodName}, or {@code -1} when no
     * function was compiled under that name.
     */
    private int funcIdForMethodName(String methodName) {
        for (int funcId = 0; funcId < methodNames.length; funcId++) {
            if (methodNames[funcId].equals(methodName)) {
                return funcId;
            }
        }
        return -1;
    }

    private Set<Integer> collectCallRefTypeIds() {
        var result = new HashSet<Integer>();
        int funcCount = module.functionSection().functionCount();
        for (int i = 0; i < funcCount; i++) {
            var body = module.codeSection().getFunctionBody(i);
            for (var ins : body.instructions()) {
                if (ins.opcode() == OpCode.CALL_REF || ins.opcode() == OpCode.RETURN_CALL_REF) {
                    result.add((int) ins.operand(0));
                }
            }
        }
        return result;
    }

    private void routeHandlerDenseFunctionsToInterpreter() {
        if (hasFixedInterpretedFunctionSet) {
            // caller supplied a fixed interpreted-function set; treat it as
            // authoritative instead of second-guessing it here.
            return;
        }
        int funcCount = module.functionSection().functionCount();
        for (int i = 0; i < funcCount; i++) {
            var body = module.codeSection().getFunctionBody(i);
            int handlerCount = 0;
            for (var ins : body.instructions()) {
                if (ins.opcode() == OpCode.TRY_TABLE && ins.catches() != null) {
                    handlerCount += ins.catches().size();
                }
            }
            if (handlerCount > MAX_TRY_TABLE_HANDLERS_PER_FUNCTION) {
                int funcId = functionImports + i;
                String functionDescription = describeFunction(funcId);
                markInterpreted(
                        funcId,
                        "Warning: using interpreted mode for "
                                + functionDescription
                                + " ("
                                + handlerCount
                                + " exception-handler blocks would overwhelm the JVM bytecode"
                                + " compiler's exception-table handling)",
                        "WASM function has too many exception-handler blocks ("
                                + handlerCount
                                + ") to compile to Java bytecode safely. It can only be run in"
                                + " the interpreter. Either reduce the number of try_table blocks"
                                + " (e.g. less aggressive inlining) or enable the interpreter"
                                + " fallback mode: "
                                + functionDescription,
                        null);
            }
        }
    }

    // For each function too large to be individually JIT-eligible (see
    // JIT_ELIGIBILITY_SIZE_BYTES above), attempts to find a set of safe cut points
    // and split it into several smaller JVM methods that stay under the budget. A
    // cut point is only used if ALL of the following hold, so the split is provably
    // behavior-preserving:
    //   - it sits exactly at a Wasm-level LABEL (a real control-flow merge point,
    //     not mid-expression);
    //   - the JVM operand stack is verified empty there via ASM's own data-flow
    //     analyzer (not guessed) -- so no values need to be threaded across the cut;
    //   - it is not the target of any backward jump (a loop header) -- cutting there
    //     would add a cross-chunk call on every loop iteration instead of once;
    //   - no branch anywhere in the function has a start/target pair that straddles
    //     it -- otherwise a jump would need to cross between two separate JVM
    //     methods, which bytecode cannot express.
    // If no such split gets every resulting chunk under budget, the function falls
    // back to the existing, already-proven interpreter delegation -- exactly like
    // the handler-density case above, so this can only ever match or improve on
    // today's behavior, never regress it.
    private void planOversizedFunctionSplits() {
        if (hasFixedInterpretedFunctionSet) {
            return;
        }
        int funcCount = module.functionSection().functionCount();
        for (int i = 0; i < funcCount; i++) {
            int funcId = functionImports + i;
            if (interpretedFunctions.contains(funcId)) {
                continue;
            }
            var analysis = analyzer.analyze(funcId);
            List<CompilerInstruction> instructions = analysis.instructions();
            if (instructions.size() <= LARGE_FUNCTION_INSTRUCTION_THRESHOLD) {
                continue;
            }

            var type = functionTypes.get(funcId);
            var body = module.codeSection().getFunctionBody(i);
            var measureCtx = newContext(funcId, type, body, analysis, "measure");

            boolean splitEligible =
                    !hasTooManyParameters(type)
                            && !type.hasObjectRefParams()
                            && !type.hasObjectRefReturns()
                            && body.localTypes().stream().noneMatch(ValType::isObjectRef)
                            && instructions.stream()
                                    .noneMatch(
                                            ins -> ins.opcode() == CompilerOpCode.TRY_CATCH_BLOCK);

            var result = analyzeAndPlanSplit(measureCtx, type, body, instructions, splitEligible);
            if (result.totalSize <= JIT_ELIGIBILITY_SIZE_BYTES) {
                continue;
            }
            if (result.cutIndices != null && !TRAMPOLINE_SPLIT_FORCED) {
                functionChunkBoundaries.put(funcId, result.cutIndices);
                if (interpreterFallback != InterpreterFallback.SILENT) {
                    System.err.println(
                            "Info: "
                                    + describeFunction(funcId)
                                    + " compiled to "
                                    + result.totalSize
                                    + " bytes of JVM bytecode, over the ~"
                                    + JIT_ELIGIBILITY_SIZE_BYTES
                                    + "-byte JIT-eligibility threshold -- split into "
                                    + (result.cutIndices.length + 1)
                                    + " chunks to stay JIT-eligible.");
                }
                continue;
            }

            // Linear chunking couldn't place a cut -- almost always because the body
            // is dominated by a loop. Try the trampoline, which isn't subject to
            // that restriction.
            if (TRAMPOLINE_SPLIT_ENABLED && splitEligible) {
                int[] starts = planTrampolineSplit(measureCtx, type, body, instructions);
                if (starts != null) {
                    trampolineChunkStarts.put(funcId, starts);
                    if (interpreterFallback != InterpreterFallback.SILENT) {
                        System.err.println(
                                "Info: "
                                        + describeFunction(funcId)
                                        + " compiled to "
                                        + result.totalSize
                                        + " bytes of JVM bytecode, over the ~"
                                        + JIT_ELIGIBILITY_SIZE_BYTES
                                        + "-byte JIT-eligibility threshold -- split into "
                                        + starts.length
                                        + " trampolined chunks to stay JIT-eligible.");
                    }
                    continue;
                }
            }

            // No safe plan was found. That is a missed optimisation, not a failure:
            // the function still compiles to a single method exactly as it did
            // before this pass existed. It stays too large for HotSpot to JIT,
            // which is the status quo rather than a regression -- so it must not
            // be pushed to the interpreter, and must not fail a build running
            // with InterpreterFallback.FAIL.
            // Deliberately silent. Reporting it would add output where there was
            // none before, for a case that is neither new nor actionable, and would
            // reorder diagnostics that callers already parse.
            if (Boolean.getBoolean("endive.debugTrampoline")) {
                System.err.println(
                        "DEBUG split: "
                                + describeFunction(funcId)
                                + " is "
                                + result.totalSize
                                + " bytes and found no safe split point; it still compiles, but"
                                + " HotSpot will not JIT it.");
            }
        }
    }

    private static final class SplitResult {
        final int totalSize;
        final int[] cutIndices; // null if the function isn't safely splittable

        SplitResult(int totalSize, int[] cutIndices) {
            this.totalSize = totalSize;
            this.cutIndices = cutIndices;
        }
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
    private int[] planTrampolineSplit(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> instructions) {

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
            if (Boolean.getBoolean("endive.debugTrampoline")) {
                System.err.println("DEBUG trampoline bail: analyzer failed: " + e);
            }
            return null;
        }

        // Only a position where the operand stack is empty can start a chunk: a
        // chunk boundary is a method boundary, and pending stack values can't be
        // carried across one (locals can -- that's what the frame array is for).
        TreeSet<Integer> candidateStarts = new TreeSet<>();
        candidateStarts.add(0);
        for (Map.Entry<Long, Integer> e : labelDefPosition.entrySet()) {
            if (isStackEmptyAtLabel(e.getKey(), labels, methodNode, frames)) {
                candidateStarts.add(e.getValue());
            }
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
                            if (Boolean.getBoolean("endive.debugTrampoline")) {
                                System.err.println(
                                        "DEBUG trampoline bail: branch at "
                                                + idx
                                                + " targets undefined label "
                                                + target);
                            }
                            return null; // branch to a label we can't place -- don't risk it
                        }
                        edges.add(new int[] {idx, defPos});
                    }
                    break;
                default:
                    break;
            }
        }

        // Per-chunk prologue/epilogue cost: every local is unboxed from the frame on
        // entry and boxed back on exit, so reserve room for it before spending the
        // budget on actual instructions.
        int totalLocals = type.params().size() + body.localTypes().size();
        int perChunkOverhead = 64 + totalLocals * 24;
        int budget = JIT_ELIGIBILITY_SIZE_BYTES - perChunkOverhead;
        if (budget < 512) {
            if (Boolean.getBoolean("endive.debugTrampoline")) {
                System.err.println("DEBUG trampoline bail: budget=" + budget);
            }
            return null; // so many locals that the sync alone would blow the budget
        }

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
                    // A branch crosses into a position that can never begin a chunk
                    // (non-empty operand stack there). Not splittable this way.
                    if (Boolean.getBoolean("endive.debugTrampoline")) {
                        System.err.println(
                                "DEBUG trampoline bail: forced start "
                                        + f
                                        + " is not a valid candidate (stack not empty there)");
                    }
                    return null;
                }
                starts.add(f);
            }
            if (starts.size() > TRAMPOLINE_MAX_CHUNKS || pass > 64) {
                if (Boolean.getBoolean("endive.debugTrampoline")) {
                    System.err.println(
                            "DEBUG trampoline bail: starts=" + starts.size() + " pass=" + pass);
                }
                return null;
            }
        }

        if (starts.size() < 2 || starts.size() > TRAMPOLINE_MAX_CHUNKS) {
            if (Boolean.getBoolean("endive.debugTrampoline")) {
                System.err.println(
                        "DEBUG trampoline bail: final starts="
                                + starts.size()
                                + " candidates="
                                + candidateStarts.size());
            }
            return null;
        }

        // Final size check: no individual chunk may exceed the budget on its own.
        List<Integer> startList = new ArrayList<>(starts);
        for (int k = 0; k < startList.size(); k++) {
            int from = startList.get(k);
            int to = (k + 1 < startList.size()) ? startList.get(k + 1) : instructions.size();
            int sizeFrom = from == 0 ? 0 : cumulativeSizeAtIndex.get(from - 1);
            int sizeTo = cumulativeSizeAtIndex.get(to - 1);
            if (sizeTo - sizeFrom > budget) {
                if (Boolean.getBoolean("endive.debugTrampoline")) {
                    System.err.println(
                            "DEBUG trampoline bail: chunk "
                                    + k
                                    + " size "
                                    + (sizeTo - sizeFrom)
                                    + " > budget "
                                    + budget);
                }
                return null;
            }
        }

        int[] result = new int[startList.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = startList.get(i);
        }
        return result;
    }

    private SplitResult analyzeAndPlanSplit(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> instructions,
            boolean splitEligible) {

        // labels referenced by at least one branch/try-catch operand (matches exactly
        // what real emission marks -- see emitFunctionBody/emitInstruction above).
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
            return new SplitResult(totalSize, null);
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
            return new SplitResult(totalSize, null);
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
                    return new SplitResult(totalSize, null);
                }
                cuts.add(lastCandidate);
                chunkStart = lastCandidate;
                chunkStartSize = cumulativeSizeAtIndex.get(lastCandidate);
                if (sizeAtIdx - chunkStartSize > JIT_ELIGIBILITY_SIZE_BYTES) {
                    return new SplitResult(totalSize, null);
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
                return new SplitResult(totalSize, null);
            }
            cuts.add(lastCandidate);
            chunkStartSize = cumulativeSizeAtIndex.get(lastCandidate);
            if (totalSize - chunkStartSize > JIT_ELIGIBILITY_SIZE_BYTES) {
                return new SplitResult(totalSize, null);
            }
        }

        if (cuts.isEmpty()) {
            return new SplitResult(totalSize, null);
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
                    return new SplitResult(totalSize, null);
                }
                int targetChunk = chunkIndexOf(defPos, cuts);
                // A label's own position is the LAST instruction of the chunk that
                // contains it (chunks are [start, cuts[k]] inclusive), so the code
                // that actually follows the label -- what a jump to it must reach --
                // only starts at the very beginning of the NEXT chunk. That's only
                // reachable via a cross-chunk call when this label's position is
                // itself exactly a chosen cut, i.e. the last instruction of its chunk.
                if (targetChunk != switchChunk && !cuts.contains(defPos)) {
                    return new SplitResult(totalSize, null);
                }
            }
        }

        return new SplitResult(totalSize, cuts.stream().mapToInt(Integer::intValue).toArray());
    }

    private String describeFunction(int funcId) {
        String functionDescription = "WASM function index: " + funcId;
        if (module.nameSection() != null) {
            String name = module.nameSection().nameOfFunction(funcId);
            if (name != null) {
                functionDescription += String.format(" (name: %s)", name);
            }
        }
        return functionDescription;
    }

    private void markInterpreted(
            int funcId, String warnMessage, String failMessage, Throwable cause) {
        switch (interpreterFallback) {
            case SILENT:
                break;
            case WARN:
                System.err.println(warnMessage);
                break;
            case FAIL:
                throw new WasmEngineException(failMessage, cause);
        }
        interpretedFunctions.add(funcId);
    }

    public static Builder builder(WasmModule module) {
        return new Builder(module);
    }

    public static final class Builder {
        private final WasmModule module;
        private String className;
        private int maxFunctionsPerClass;
        private InterpreterFallback interpreterFallback;
        private Set<Integer> interpretedFunctions;
        private Supplier<ClassCollector> classCollectorFactory;
        private MethodPrefixer methodPrefixer;

        private Builder(WasmModule module) {
            this.module = module;
        }

        public Builder withClassName(String className) {
            this.className = className;
            return this;
        }

        public Builder withMaxFunctionsPerClass(int maxFunctionsPerClass) {
            this.maxFunctionsPerClass = maxFunctionsPerClass;
            return this;
        }

        public Builder withInterpreterFallback(InterpreterFallback interpreterFallback) {
            this.interpreterFallback = interpreterFallback;
            return this;
        }

        public Builder withInterpretedFunctions(Set<Integer> interpretedFunctions) {
            this.interpretedFunctions = interpretedFunctions;
            return this;
        }

        public Builder withClassCollectorFactory(Supplier<ClassCollector> classCollectorFactory) {
            this.classCollectorFactory = classCollectorFactory;
            return this;
        }

        public Builder withMethodPrefixer(MethodPrefixer methodPrefixer) {
            this.methodPrefixer = methodPrefixer;
            return this;
        }

        public Compiler build() {
            var className = this.className;
            if (className == null) {
                className = DEFAULT_CLASS_NAME;
            }

            int maxFunctionsPerClass = this.maxFunctionsPerClass;
            if (maxFunctionsPerClass <= 0) {
                maxFunctionsPerClass = DEFAULT_MAX_FUNCTIONS_PER_CLASS;
            }

            if (this.classCollectorFactory == null) {
                this.classCollectorFactory = ClassLoadingCollector::new;
            }

            return new Compiler(
                    module,
                    className,
                    maxFunctionsPerClass,
                    interpreterFallback,
                    interpretedFunctions,
                    classCollectorFactory,
                    methodPrefixer);
        }
    }

    public CompilerResult compile() {
        while (true) {
            try {
                compileExtraClasses();
                var bytes = compileClass();
                collector.putMainClass(className, bytes);
                break;
            } catch (ClassTooLargeException e) {
                if (useBridgeClasses) {
                    throw e;
                }
                // retry with call_indirect methods split into bridge classes
                useBridgeClasses = true;
                collector = classCollectorFactory.get();
            }
        }
        return new CompilerResult(collector, Set.copyOf(interpretedFunctions));
    }

    private void compileExtraClasses() {
        createShadedClass(className, collector);

        // When call_indirect methods are moved to bridge classes, compile them first
        // so the resolver is built before FuncGroup compilation
        if (useBridgeClasses) {
            compileCallIndirectBridgeClasses();
        }

        int totalFunctions = functionImports + module.functionSection().functionCount();

        // Emit the "${className}FuncGroup_${chunk}" classes:
        // We group the wasm functions into chunks to avoid MethodTooLargeException or
        // ClassTooLargeException.
        // The chunk size is dynamically adjusted based on the size of the class to be loaded and
        // the maximum size
        // that can be loaded without causing an exception.
        //
        // Example: wasm file has 1024 * 15 functions.  Then the first 12k functions will be located
        // in "${className}FuncGroup_0" and the last 3k functions will be located in
        // "${className}FuncGroup_1".
        // That is if no  MethodTooLargeException or ClassTooLargeException occur when generating
        // those two classes.
        // This can happen if for example a function requires alot of class constants etc.   Then
        // default
        // chunk size of 12k will be halved to 6k and the first 6k functions will be located in
        // "${className}FuncGroup_0", "${className}FuncGroup_1", and  "${className}FuncGroup_2" with
        // each class holding up to 6k of the functions.
        //
        var originalMaxFunctionsPerClass = maxFunctionsPerClass;
        while (true) {
            try {
                maxFunctionsPerClass =
                        loadChunkedClass(
                                totalFunctions,
                                maxFunctionsPerClass,
                                (collector, start, end, chunkSize) -> {
                                    maxFunctionsPerClass = chunkSize;
                                    String className = classNameForFuncGroup(this.className, start);
                                    compileExtraClass(
                                            collector,
                                            className,
                                            emitFunctionGroup(
                                                    start, end, internalClassName(this.className)));
                                });
                break;
            } catch (MethodTooLargeException e) {
                String methodName = e.getMethodName();
                int funcId = funcIdForMethodName(methodName);
                if (funcId >= 0) {
                    String functionDescription = describeFunction(funcId);

                    markInterpreted(
                            funcId,
                            "Warning: using interpreted mode for " + functionDescription,
                            "WASM function size exceeds the Java method size limits and cannot be"
                                + " compiled to Java bytecode. It can only be run in the"
                                + " interpreter. Either reduce the size of the function or enable"
                                + " the interpreter fallback mode: "
                                    + functionDescription,
                            e);

                    maxFunctionsPerClass = originalMaxFunctionsPerClass;
                } else {
                    throw e;
                }
            }
        }

        if (!functionTypes.isEmpty()) {
            compileMachineCallClass();
        }
    }

    private void compileCallIndirectBridgeClasses() {
        var allTypes = module.typeSection().types();
        var mainInternalClassName = internalClassName(className);

        var chunkSize =
                loadChunkedClass(
                        allTypes.length,
                        DEFAULT_MAX_FUNCTIONS_PER_CLASS,
                        (collector, start, end, cs) -> {
                            String bridgeClassName = className + "CallIndirectBridge_" + start;
                            compileExtraClass(
                                    collector,
                                    bridgeClassName,
                                    cw -> {
                                        for (int i = start; i < end; i++) {
                                            var typeId = i;
                                            var type = allTypes[i];
                                            if (type == null) {
                                                continue;
                                            }
                                            emitFunction(
                                                    cw,
                                                    callIndirectMethodName(typeId),
                                                    callIndirectMethodType(type),
                                                    true,
                                                    asm ->
                                                            compileCallIndirect(
                                                                    mainInternalClassName,
                                                                    typeId,
                                                                    type,
                                                                    asm));
                                        }
                                    });
                        });

        var bridgePrefix = internalClassName(className) + "CallIndirectBridge_";
        callIndirectClassResolver =
                typeId -> {
                    int start = (typeId / chunkSize) * chunkSize;
                    return bridgePrefix + start;
                };
    }

    interface ChunkedClassEmitter {
        void emit(ClassCollector collector, int start, int end, int chunkSize);
    }

    /**
     * Loads a chunked class based on the given size and chunk size.
     * <p>
     * This method attempts to load a class in chunks to avoid MethodTooLargeException or ClassTooLargeException.
     * It dynamically adjusts the chunk size based on the size of the class to be loaded and the maximum size
     * that can be loaded without causing an exception. The method returns the final chunk size used for loading.
     *
     * @param size      The total size of the class to be loaded.
     * @param chunkSize The initial chunk size to use for loading.
     * @param emitter   The ChunkedClassEmitter that generates the class bytes for a given chunk.
     * @return The final chunk size used for loading the class.
     */
    int loadChunkedClass(int size, int chunkSize, ChunkedClassEmitter emitter) {
        // Create a temporary collector to verify class construction
        // (if the collector supports throwing ClassTooLargeException).
        ClassCollector collector = classCollectorFactory.get();
        while (true) {
            try {
                int chunks = (size / chunkSize) + (size % chunkSize == 0 ? 0 : 1);
                for (int i = 0; i < chunks; i++) {
                    var start = i * chunkSize;
                    var end = min(start + chunkSize, size);

                    emitter.emit(collector, start, end, chunkSize);
                }
                break;
            } catch (ClassTooLargeException e) {
                chunkSize = chunkSize >> 1;
                if (chunkSize == 0) {
                    throw e;
                }
                collector = classCollectorFactory.get();
            }
        }

        // Store the final results into the global collector.
        this.collector.putAll(collector);
        return chunkSize;
    }

    private String classNameForFuncGroup(String prefix, int funcId) {
        return prefix + "FuncGroup_" + (funcId / maxFunctionsPerClass);
    }

    private Consumer<ClassVisitor> emitFunctionGroup(int start, int end, String internalClassName) {
        return (classWriter) -> {
            for (int i = start; i < end; i++) {
                FunctionBody body;
                try {
                    int funcId = i;
                    var type = functionTypes.get(funcId);

                    // is it an import function?
                    if (i < functionImports) {
                        emitFunction(
                                classWriter,
                                methodName(funcId),
                                methodTypeFor(type),
                                true,
                                asm -> compileHostFunction(funcId, type, asm));

                    } else {
                        body = module.codeSection().getFunctionBody(i - functionImports);
                        var bodyCopy = body;

                        emitFunction(
                                classWriter,
                                methodName(funcId),
                                methodTypeFor(type),
                                true,
                                asm ->
                                        compileFunction(
                                                classWriter,
                                                internalClassName,
                                                funcId,
                                                type,
                                                bodyCopy,
                                                asm));

                        // call_xxx() bridges for boxed to native
                        emitFunction(
                                classWriter,
                                callMethodName(funcId),
                                CALL_METHOD_TYPE_WITH_REFS,
                                true,
                                asm -> compileCallFunction(funcId, type, asm));

                        // callWithRefs_xxx() bridges for functions with Object refs
                        if (type.hasObjectRefParams() || type.hasObjectRefReturns()) {
                            emitFunction(
                                    classWriter,
                                    callWithRefsMethodName(funcId),
                                    CALL_WITH_REFS_BRIDGE_METHOD_TYPE,
                                    true,
                                    asm -> compileCallWithRefsFunction(funcId, type, asm));
                        }
                    }
                } catch (MethodTooLargeException e) {
                    throw handleMethodTooLarge(e);
                }
            }
        };
    }

    private byte[] compileClass() {
        var internalClassName = internalClassName(className);

        ClassWriter binaryWriter = newClassWriter();
        ClassVisitor classWriter = shadedClassRemapper(binaryWriter, className);

        classWriter.visit(
                Opcodes.V11,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalClassName,
                null,
                getInternalName(Object.class),
                new String[] {getInternalName(Machine.class)});

        classWriter.visitSource("wasm", null);

        classWriter.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "instance",
                getDescriptor(Instance.class),
                null,
                null);

        classWriter.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "compilerInterpreterMachine",
                getDescriptor(CompilerInterpreterMachine.class),
                null,
                null);

        // constructor
        emitFunction(
                classWriter,
                "<init>",
                methodType(void.class, Instance.class),
                false,
                asm -> compileConstructor(asm, internalClassName));

        // Machine.call(int, long[]) implementation — 2-arg overload
        // Locals: 0=this, 1=funcId, 2=args; store null refArgs at slot 3
        emitFunction(
                classWriter,
                "call",
                methodType(long[].class, int.class, long[].class),
                false,
                asm -> {
                    asm.aconst(null);
                    asm.store(3, OBJECT_TYPE); // refArgs = null
                    compileMachineCall(internalClassName, asm, 3);
                });

        // Machine.call(int, long[], Object[]) implementation — 3-arg overload
        // Locals: 0=this, 1=funcId, 2=args, 3=refArgs
        emitFunction(
                classWriter,
                "call",
                methodType(long[].class, int.class, long[].class, Object[].class),
                false,
                asm -> compileMachineCall(internalClassName, asm, 3));

        // Machine.callWithRefs(int, long[], Object[]) implementation
        // Dispatches directly to compiled callWithRefs_N bridges (or call_N for non-ref
        // functions), bypassing the interpreter. Interpreted functions still fall back
        // to compilerInterpreterMachine.callWithRefs().
        emitFunction(
                classWriter,
                "callWithRefs",
                methodType(CallResult.class, int.class, long[].class, Object[].class),
                false,
                asm -> compileMachineCallWithRefs(internalClassName, asm));

        // call_indirect_xxx() bridges for native CALL_INDIRECT
        // When using bridge classes, these methods are on separate classes
        if (!useBridgeClasses) {
            var allTypes = module.typeSection().types();
            for (int i = 0; i < allTypes.length; i++) {
                var typeId = i;
                var type = allTypes[i];
                if (type == null) {
                    continue; // skip non-function types (struct/array)
                }
                emitFunction(
                        classWriter,
                        callIndirectMethodName(typeId),
                        callIndirectMethodType(type),
                        true,
                        asm -> compileCallIndirect(internalClassName, typeId, type, asm));
            }
        }

        // value_xxx() bridges for multi-value return
        // Deduplicate by method name, not by type list, because different
        // reference types (e.g. RefNull(funcref) vs RefNull(externref)) produce
        // the same JVM method name and signature.
        var seenValueMethods = new java.util.HashSet<String>();
        var returnTypes =
                functionTypes.stream()
                        .map(FunctionType::returns)
                        .filter(types -> types.size() > 1)
                        .collect(toSet());
        for (var types : returnTypes) {
            var methodName = valueMethodName(types);
            if (!seenValueMethods.add(methodName)) {
                continue;
            }
            boolean hasGcRef = types.stream().anyMatch(ValType::isObjectRef);
            emitFunction(
                    classWriter,
                    methodName,
                    valueMethodType(types),
                    true,
                    asm -> {
                        if (hasGcRef) {
                            // Object[] return: box numerics as Long, keep refs as Object
                            emitBoxArgumentsAsObjectArray(asm, types);
                        } else {
                            emitBoxArguments(asm, types);
                        }
                        asm.areturn(OBJECT_TYPE);
                    });
        }

        classWriter.visitEnd();

        try {
            return binaryWriter.toByteArray();
        } catch (MethodTooLargeException e) {
            throw handleMethodTooLarge(e);
        }
    }

    /**
     * Check if function funcIdx has a type that matches typeId for call_indirect.
     * Considers type index equality, subtyping, and canonical equivalence.
     */
    private boolean isFuncTypeMatch(int expectedTypeId, int funcIdx, FunctionType expectedType) {
        int funcTypeIdx = analyzer.functionTypeIndex(funcIdx);
        if (expectedTypeId == funcTypeIdx) {
            return true;
        }
        var ts = module.typeSection();
        if (ts != null) {
            return ValType.heapTypeSubtype(funcTypeIdx, expectedTypeId, ts);
        }
        return expectedType.equals(functionTypes.get(funcIdx));
    }

    private RuntimeException handleMethodTooLarge(MethodTooLargeException e) {
        String name = e.getMethodName();
        int funcId = funcIdForMethodName(name);
        if (funcId >= 0 && module.nameSection() != null) {
            String function = module.nameSection().nameOfFunction(funcId);
            if (function != null) {
                name += " (" + function + ")";
            }
        }
        return new WasmEngineException(
                String.format(
                        "JVM bytecode too large for WASM method: %s size=%d",
                        name, e.getCodeSize()),
                e);
    }

    private static void emitFunction(
            ClassVisitor classWriter,
            String methodName,
            MethodType methodType,
            boolean isStatic,
            Consumer<InstructionAdapter> consumer) {

        var methodWriter =
                classWriter.visitMethod(
                        Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0),
                        methodName,
                        methodType.toMethodDescriptorString(),
                        null,
                        null);

        // uncomment if you ever want to troubleshoot invalid bytecode generation
        // methodWriter = new CheckMethodAdapter(methodWriter);

        methodWriter.visitCode();
        consumer.accept(new InstructionAdapter(methodWriter));
        methodWriter.visitMaxs(0, 0);
        methodWriter.visitEnd();
    }

    private static void emitCallSuper(InstructionAdapter asm) {
        asm.load(0, OBJECT_TYPE);
        asm.invokespecial(
                OBJECT_TYPE.getInternalName(), "<init>", getMethodDescriptor(VOID_TYPE), false);
    }

    private void compileConstructor(InstructionAdapter asm, String internalClassName) {
        emitCallSuper(asm);

        // this.instance = instance;
        asm.load(0, OBJECT_TYPE);
        asm.load(1, OBJECT_TYPE);
        asm.putfield(internalClassName, "instance", getDescriptor(Instance.class));

        // Only create compilerInterpreterMachine when needed:
        // - interpreted functions need it for fallback execution
        // callWithRefs dispatches directly to compiled bridges now,
        // so moduleHasObjectRefs alone no longer requires the interpreter
        if (!interpretedFunctions.isEmpty()) {
            asm.load(0, OBJECT_TYPE);
            asm.anew(AOT_INTERPRETER_MACHINE_TYPE);
            asm.dup();
            asm.load(1, OBJECT_TYPE);

            // construct int[] with the interpreted function ids
            var funcIds = new ArrayList<>(interpretedFunctions);
            asm.iconst(funcIds.size());
            asm.newarray(INT_TYPE);
            for (int i = 0; i < funcIds.size(); i++) {
                asm.dup();
                asm.iconst(i);
                asm.iconst(funcIds.get(i));
                asm.astore(INT_TYPE);
            }

            asm.invokespecial(
                    AOT_INTERPRETER_MACHINE_TYPE.getInternalName(),
                    "<init>",
                    getMethodDescriptor(VOID_TYPE, INSTANCE_TYPE, INT_ARRAY_TYPE),
                    false);
            asm.putfield(
                    internalClassName,
                    "compilerInterpreterMachine",
                    getDescriptor(CompilerInterpreterMachine.class));
        }

        asm.areturn(VOID_TYPE);
    }

    // implements the body of:
    // public long[] call(int var1, long[] var2)
    // or
    // public long[] call(int var1, long[] var2, Object[] var3)
    //
    // refArgsSlot: the local variable slot holding Object[] refArgs
    private void compileMachineCall(
            String internalClassName, InstructionAdapter asm, int refArgsSlot) {

        // handle modules with no functions
        if (functionTypes.isEmpty()) {
            asm.load(1, INT_TYPE);
            emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION);
            asm.athrow();
            return;
        }

        if (!interpretedFunctions.isEmpty()) {
            Label invalid = new Label();
            int[] keys = interpretedFunctions.stream().mapToInt(x -> x).sorted().toArray();
            Label[] labels =
                    interpretedFunctions.stream().map(x -> new Label()).toArray(Label[]::new);
            asm.load(1, INT_TYPE);
            asm.lookupswitch(invalid, keys, labels);
            for (int i = 0; i < interpretedFunctions.size(); i++) {
                // case 0:
                //    return this.compilerInterpreterMachine.call(var1, var2);
                asm.mark(labels[i]);

                asm.load(0, OBJECT_TYPE);
                asm.getfield(
                        internalClassName,
                        "compilerInterpreterMachine",
                        getDescriptor(CompilerInterpreterMachine.class));
                asm.load(1, INT_TYPE);
                asm.load(2, OBJECT_TYPE);

                asm.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        AOT_INTERPRETER_MACHINE_TYPE.getInternalName(),
                        "call",
                        getMethodDescriptor(AOT_INTERPRETER_MACHINE_CALL),
                        false);
                asm.areturn(OBJECT_TYPE);
            }
            asm.mark(invalid);
        }

        if (moduleHasTailCalls) {
            compileMachineCallWithTailCalls(internalClassName, asm, refArgsSlot);
        } else {
            compileMachineCallSimple(internalClassName, asm, refArgsSlot);
        }
    }

    private void compileMachineCallSimple(
            String internalClassName, InstructionAdapter asm, int refArgsSlot) {
        Label start = new Label();
        Label end = new Label();
        asm.visitTryCatchBlock(start, end, end, getInternalName(StackOverflowError.class));
        asm.mark(start);

        asm.load(0, OBJECT_TYPE);
        asm.getfield(internalClassName, "instance", getDescriptor(Instance.class));
        asm.dup();
        emitInvokeVirtual(asm, INSTANCE_MEMORY);
        asm.load(1, INT_TYPE);
        asm.load(2, OBJECT_TYPE);
        asm.load(refArgsSlot, OBJECT_TYPE); // refArgs (null for 2-arg, actual for 3-arg)

        asm.invokestatic(
                internalClassName + "MachineCall",
                "call",
                MACHINE_CALL_METHOD_TYPE_WITH_REFS.toMethodDescriptorString(),
                false);
        asm.areturn(OBJECT_TYPE);

        // catch StackOverflow
        asm.mark(end);
        emitInvokeStatic(asm, THROW_CALL_STACK_EXHAUSTED);
        asm.athrow();
    }

    // implements the body of:
    // public CallResult callWithRefs(int funcId, long[] args, Object[] refArgs)
    // Locals: 0=this, 1=funcId, 2=args, 3=refArgs
    private void compileMachineCallWithRefs(String internalClassName, InstructionAdapter asm) {

        // handle modules with no functions
        if (functionTypes.isEmpty()) {
            asm.load(1, INT_TYPE);
            emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION);
            asm.athrow();
            return;
        }

        // Interpreted functions: delegate to compilerInterpreterMachine.callWithRefs()
        if (!interpretedFunctions.isEmpty()) {
            Label invalid = new Label();
            int[] keys = interpretedFunctions.stream().mapToInt(x -> x).sorted().toArray();
            Label[] labels =
                    interpretedFunctions.stream().map(x -> new Label()).toArray(Label[]::new);
            asm.load(1, INT_TYPE);
            asm.lookupswitch(invalid, keys, labels);
            for (int i = 0; i < interpretedFunctions.size(); i++) {
                asm.mark(labels[i]);
                asm.load(0, OBJECT_TYPE);
                asm.getfield(
                        internalClassName,
                        "compilerInterpreterMachine",
                        getDescriptor(CompilerInterpreterMachine.class));
                asm.load(1, INT_TYPE);
                asm.load(2, OBJECT_TYPE);
                asm.load(3, OBJECT_TYPE);
                asm.invokevirtual(
                        AOT_INTERPRETER_MACHINE_TYPE.getInternalName(),
                        "callWithRefs",
                        Type.getMethodDescriptor(
                                Type.getType(CallResult.class),
                                INT_TYPE,
                                LONG_ARRAY_TYPE,
                                Type.getType(Object[].class)),
                        false);
                asm.areturn(OBJECT_TYPE);
            }
            asm.mark(invalid);
        }

        if (moduleHasObjectRefs) {
            // Module has Object refs: dispatch through MachineCall.callWithRefs()
            // which routes to callWithRefs_N for ref functions and wraps call_N for non-ref
            Label start = new Label();
            Label end = new Label();
            asm.visitTryCatchBlock(start, end, end, getInternalName(StackOverflowError.class));
            asm.mark(start);

            asm.load(0, OBJECT_TYPE);
            asm.getfield(internalClassName, "instance", getDescriptor(Instance.class));
            asm.dup();
            emitInvokeVirtual(asm, INSTANCE_MEMORY);
            asm.load(1, INT_TYPE);
            asm.load(2, OBJECT_TYPE);
            asm.load(3, OBJECT_TYPE);

            asm.invokestatic(
                    internalClassName + "MachineCall",
                    "callWithRefs",
                    MACHINE_CALL_WITH_REFS_METHOD_TYPE.toMethodDescriptorString(),
                    false);
            asm.areturn(OBJECT_TYPE);

            asm.mark(end);
            emitInvokeStatic(asm, THROW_CALL_STACK_EXHAUSTED);
            asm.athrow();
        } else {
            // No Object refs in module: wrap call() result in CallResult(longs, null)
            asm.load(0, OBJECT_TYPE);
            asm.load(1, INT_TYPE);
            asm.load(2, OBJECT_TYPE);
            asm.load(3, OBJECT_TYPE);
            asm.invokevirtual(
                    internalClassName,
                    "call",
                    Type.getMethodDescriptor(
                            LONG_ARRAY_TYPE,
                            INT_TYPE,
                            LONG_ARRAY_TYPE,
                            Type.getType(Object[].class)),
                    false);
            asm.aconst(null);
            asm.invokestatic(CALL_RESULT_INTERNAL_NAME, "of", CALL_RESULT_OF_DESCRIPTOR, false);
            asm.areturn(OBJECT_TYPE);
        }
    }

    // Generates a trampoline loop equivalent to:
    //
    //   Instance inst = this.instance;
    //   while (true) {
    //       try {
    //           long[] result = MachineCall.call(inst, inst.memory(), funcId, args);
    //           if (!inst.isTailCallPending()) return result;
    //           funcId = inst.tailCallFuncId();
    //           args = inst.tailCallArgs();
    //           inst.clearTailCall();
    //       } catch (StackOverflowError e) {
    //           throw throwCallStackExhausted(e);
    //       }
    //   }
    private void compileMachineCallWithTailCalls(
            String internalClassName, InstructionAdapter asm, int refArgsSlot) {
        Label start = new Label();
        Label end = new Label();
        Label soeCatch = new Label();
        asm.visitTryCatchBlock(start, end, soeCatch, getInternalName(StackOverflowError.class));
        asm.mark(start);

        asm.load(0, OBJECT_TYPE);
        asm.getfield(internalClassName, "instance", getDescriptor(Instance.class));
        // instance stored at local 4 for 2-arg, local 5 for 3-arg
        int instanceLocal = refArgsSlot + 1;
        asm.store(instanceLocal, OBJECT_TYPE);

        Label loopStart = new Label();
        asm.mark(loopStart);

        asm.load(instanceLocal, OBJECT_TYPE);
        asm.dup();
        emitInvokeVirtual(asm, INSTANCE_MEMORY);
        asm.load(1, INT_TYPE);
        asm.load(2, OBJECT_TYPE);
        asm.load(refArgsSlot, OBJECT_TYPE); // refArgs

        asm.invokestatic(
                internalClassName + "MachineCall",
                "call",
                MACHINE_CALL_METHOD_TYPE_WITH_REFS.toMethodDescriptorString(),
                false);

        asm.load(instanceLocal, OBJECT_TYPE);
        asm.invokevirtual(
                getInternalName(Instance.class),
                "isTailCallPending",
                getMethodDescriptor(Type.BOOLEAN_TYPE),
                false);
        Label returnResult = new Label();
        asm.ifeq(returnResult);

        asm.pop();
        asm.load(instanceLocal, OBJECT_TYPE);
        asm.invokevirtual(
                getInternalName(Instance.class),
                "tailCallFuncId",
                getMethodDescriptor(INT_TYPE),
                false);
        asm.store(1, INT_TYPE);
        asm.load(instanceLocal, OBJECT_TYPE);
        asm.invokevirtual(
                getInternalName(Instance.class),
                "tailCallArgs",
                getMethodDescriptor(getType(long[].class)),
                false);
        asm.store(2, OBJECT_TYPE);
        // Load refArgs from tail call pending
        asm.load(instanceLocal, OBJECT_TYPE);
        asm.invokevirtual(
                getInternalName(Instance.class),
                "tailCallRefArgs",
                getMethodDescriptor(getType(Object[].class)),
                false);
        asm.store(refArgsSlot, OBJECT_TYPE);
        asm.load(instanceLocal, OBJECT_TYPE);
        asm.invokevirtual(
                getInternalName(Instance.class),
                "clearTailCall",
                getMethodDescriptor(VOID_TYPE),
                false);
        asm.goTo(loopStart);

        asm.mark(returnResult);
        asm.mark(end);
        asm.areturn(OBJECT_TYPE);

        // catch StackOverflow
        asm.mark(soeCatch);
        emitInvokeStatic(asm, THROW_CALL_STACK_EXHAUSTED);
        asm.athrow();
    }

    private void compileMachineCallClass() {
        ClassWriter binaryWriter = newClassWriter();
        ClassVisitor classWriter = shadedClassRemapper(binaryWriter, className);
        String machineCallClassName = internalClassName(className + "MachineCall");

        classWriter.visit(
                Opcodes.V11,
                Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                machineCallClassName,
                null,
                getInternalName(Object.class),
                null);

        // constructor
        emitFunction(
                classWriter,
                "<init>",
                methodType(void.class),
                false,
                asm -> {
                    emitCallSuper(asm);
                    asm.areturn(VOID_TYPE);
                });

        // static implementation for Machine.call()
        Consumer<InstructionAdapter> callMethod;
        if (functionTypes.size() < MAX_DISPATCH_METHODS) {
            callMethod = asm -> compileMachineCallInvoke(asm, 0, functionTypes.size());
        } else {
            // Best value that worked with the 50K small wasm functions
            var maxMachineCallMethods = MAX_DISPATCH_METHODS << 2;
            maxMachineCallMethods =
                    loadChunkedClass(
                            functionTypes.size(),
                            maxMachineCallMethods,
                            (collector, start, end, chunkSize) ->
                                    compileExtraClass(
                                            collector,
                                            classNameForDispatch(className, start),
                                            (cw) ->
                                                    emitFunction(
                                                            cw,
                                                            callDispatchMethodName(start),
                                                            MACHINE_CALL_METHOD_TYPE_WITH_REFS,
                                                            true,
                                                            asm ->
                                                                    compileMachineCallInvoke(
                                                                            asm, start, end))));
            callMethod = compileMachineCallDispatch(maxMachineCallMethods);
        }
        emitFunction(classWriter, "call", MACHINE_CALL_METHOD_TYPE_WITH_REFS, true, callMethod);

        // static implementation for Machine.callWithRefs() — only when module has Object refs
        if (moduleHasObjectRefs) {
            Consumer<InstructionAdapter> callWithRefsMethod;
            if (functionTypes.size() < MAX_DISPATCH_METHODS) {
                callWithRefsMethod =
                        asm -> compileMachineCallWithRefsInvoke(asm, 0, functionTypes.size());
            } else {
                var maxCallWithRefsMethods = MAX_DISPATCH_METHODS << 2;
                maxCallWithRefsMethods =
                        loadChunkedClass(
                                functionTypes.size(),
                                maxCallWithRefsMethods,
                                (coll, start, end, chunkSize) ->
                                        compileExtraClass(
                                                coll,
                                                classNameForCallWithRefsDispatch(className, start),
                                                (cw) ->
                                                        emitFunction(
                                                                cw,
                                                                callWithRefsDispatchMethodName(
                                                                        start),
                                                                MACHINE_CALL_WITH_REFS_METHOD_TYPE,
                                                                true,
                                                                asm ->
                                                                        compileMachineCallWithRefsInvoke(
                                                                                asm, start, end))));
                callWithRefsMethod = compileMachineCallWithRefsDispatch(maxCallWithRefsMethods);
            }
            emitFunction(
                    classWriter,
                    "callWithRefs",
                    MACHINE_CALL_WITH_REFS_METHOD_TYPE,
                    true,
                    callWithRefsMethod);
        }

        collector.put(machineCallClassName, binaryWriter.toByteArray());
    }

    private void compileExtraClass(
            ClassCollector collector, String name, Consumer<ClassVisitor> consumer) {
        ClassWriter binaryWriter = newClassWriter();
        ClassVisitor classWriter = shadedClassRemapper(binaryWriter, className);
        String internalClassName = internalClassName(name);
        classWriter.visit(
                Opcodes.V11,
                Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalClassName,
                null,
                getInternalName(Object.class),
                null);
        consumer.accept(classWriter);
        collector.put(name, binaryWriter.toByteArray());
    }

    private Consumer<InstructionAdapter> compileMachineCallDispatch(int maxMachineCallMethods) {
        return (asm) -> {

            // load arguments: instance, memory, funcId, args, refArgs
            asm.load(0, OBJECT_TYPE);
            asm.load(1, OBJECT_TYPE);
            asm.load(2, INT_TYPE);
            asm.load(3, OBJECT_TYPE);
            asm.load(4, OBJECT_TYPE); // refArgs

            assert Integer.bitCount(maxMachineCallMethods) == 1; // power of two
            int shift = Integer.numberOfTrailingZeros(maxMachineCallMethods);

            // switch (funcId >> shift)
            Label[] labels = new Label[((functionTypes.size() - 1) >> shift) + 1];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = new Label();
            }

            asm.load(2, INT_TYPE);
            asm.iconst(shift);
            asm.shr(INT_TYPE);
            asm.tableswitch(0, labels.length - 1, labels[0], labels);

            // return call_dispatch_xxx(instance, memory, funcId, args, refArgs);
            for (int i = 0; i < labels.length; i++) {
                asm.mark(labels[i]);
                asm.invokestatic(
                        internalClassName(classNameForDispatch(className, i << shift)),
                        callDispatchMethodName(i << shift),
                        MACHINE_CALL_METHOD_TYPE_WITH_REFS.toMethodDescriptorString(),
                        false);
                asm.areturn(OBJECT_TYPE);
            }
        };
    }

    private void compileMachineCallInvoke(InstructionAdapter asm, int start, int end) {
        // load arguments: instance, memory, args, refArgs
        asm.load(0, OBJECT_TYPE);
        asm.load(1, OBJECT_TYPE);
        asm.load(3, OBJECT_TYPE);
        asm.load(4, OBJECT_TYPE); // refArgs

        // switch (funcId)
        Label defaultLabel = new Label();
        Label hostLabel = new Label();
        Label[] labels = new Label[end - start];

        for (int id = start; id < end; id++) {
            labels[id - start] = (id < functionImports) ? hostLabel : new Label();
        }

        asm.load(2, INT_TYPE);
        asm.tableswitch(start, end - 1, defaultLabel, labels);

        // return call_xxx(instance, memory, args, refArgs);
        for (int id = max(start, functionImports); id < end; id++) {
            asm.mark(labels[id - start]);
            asm.invokestatic(
                    internalClassName(classNameForFuncGroup(className, id)),
                    callMethodName(id),
                    CALL_METHOD_TYPE_WITH_REFS.toMethodDescriptorString(),
                    false);
            asm.areturn(OBJECT_TYPE);
        }

        // return instance.callHostFunctionWithRefs(funcId, args, refArgs)
        // We always use WithRefs here since the generic dispatch doesn't know
        // at emit time whether the function type has Object refs.
        // CallResult.longs() is returned to the Machine.call() caller.
        if (functionImports > start) {
            asm.mark(hostLabel);
            // stack: instance, memory, args, refArgs
            asm.pop(); // pop refArgs -> save it
            // Actually we need the args: instance, memory, args, refArgs are local 0-4
            // Pop all stacked values (instance, memory, args, refArgs were loaded)
            asm.pop();
            asm.pop();
            asm.pop();
            // Use locals directly
            asm.load(0, OBJECT_TYPE); // instance
            asm.load(2, INT_TYPE); // funcId
            asm.load(3, OBJECT_TYPE); // args
            asm.load(4, OBJECT_TYPE); // refArgs
            emitInvokeStatic(asm, CALL_HOST_FUNCTION_WITH_REFS);
            // CallResult on stack, extract longs() for the long[] return
            asm.invokevirtual(CALL_RESULT_INTERNAL_NAME, "longs", "()[J", false);
            asm.areturn(OBJECT_TYPE);
        }

        // throw new InvalidException("unknown function " + funcId);
        asm.mark(defaultLabel);
        asm.load(2, INT_TYPE);
        emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION);
        asm.athrow();
    }

    private Consumer<InstructionAdapter> compileMachineCallWithRefsDispatch(
            int maxMachineCallMethods) {
        return (asm) -> {
            // load arguments: instance, memory, funcId, args, refArgs
            asm.load(0, OBJECT_TYPE);
            asm.load(1, OBJECT_TYPE);
            asm.load(2, INT_TYPE);
            asm.load(3, OBJECT_TYPE);
            asm.load(4, OBJECT_TYPE);

            assert Integer.bitCount(maxMachineCallMethods) == 1; // power of two
            int shift = Integer.numberOfTrailingZeros(maxMachineCallMethods);

            Label[] labels = new Label[((functionTypes.size() - 1) >> shift) + 1];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = new Label();
            }

            asm.load(2, INT_TYPE);
            asm.iconst(shift);
            asm.shr(INT_TYPE);
            asm.tableswitch(0, labels.length - 1, labels[0], labels);

            for (int i = 0; i < labels.length; i++) {
                asm.mark(labels[i]);
                asm.invokestatic(
                        internalClassName(classNameForCallWithRefsDispatch(className, i << shift)),
                        callWithRefsDispatchMethodName(i << shift),
                        MACHINE_CALL_WITH_REFS_METHOD_TYPE.toMethodDescriptorString(),
                        false);
                asm.areturn(OBJECT_TYPE);
            }
        };
    }

    private void compileMachineCallWithRefsInvoke(InstructionAdapter asm, int start, int end) {

        // load arguments: instance, memory, args, refArgs
        asm.load(0, OBJECT_TYPE);
        asm.load(1, OBJECT_TYPE);
        asm.load(3, OBJECT_TYPE);
        asm.load(4, OBJECT_TYPE);

        // switch (funcId)
        Label defaultLabel = new Label();
        Label hostLabel = new Label();
        Label[] labels = new Label[end - start];

        for (int id = start; id < end; id++) {
            labels[id - start] = (id < functionImports) ? hostLabel : new Label();
        }

        asm.load(2, INT_TYPE);
        asm.tableswitch(start, end - 1, defaultLabel, labels);

        // For each compiled function:
        // - If it has Object refs: call callWithRefs_N -> returns CallResult directly
        // - Otherwise: call call_N -> wrap result in CallResult(longs, null)
        for (int id = max(start, functionImports); id < end; id++) {
            asm.mark(labels[id - start]);
            var type = functionTypes.get(id);
            if (type.hasObjectRefParams() || type.hasObjectRefReturns()) {
                // return callWithRefs_N(instance, memory, args, refArgs)
                asm.invokestatic(
                        internalClassName(classNameForFuncGroup(className, id)),
                        callWithRefsMethodName(id),
                        CALL_WITH_REFS_BRIDGE_METHOD_TYPE.toMethodDescriptorString(),
                        false);
            } else {
                // return new CallResult(call_N(instance, memory, args, refArgs), null)
                asm.invokestatic(
                        internalClassName(classNameForFuncGroup(className, id)),
                        callMethodName(id),
                        CALL_METHOD_TYPE_WITH_REFS.toMethodDescriptorString(),
                        false);
                // Wrap long[] in CallResult.of(longs, null)
                asm.aconst(null);
                asm.invokestatic(CALL_RESULT_INTERNAL_NAME, "of", CALL_RESULT_OF_DESCRIPTOR, false);
            }
            asm.areturn(OBJECT_TYPE);
        }

        // Host function: return callHostFunctionWithRefs(instance, funcId, args, refArgs)
        if (functionImports > start) {
            asm.mark(hostLabel);
            asm.pop();
            asm.pop();
            asm.pop();
            asm.pop();
            asm.load(0, OBJECT_TYPE); // instance
            asm.load(2, INT_TYPE); // funcId
            asm.load(3, OBJECT_TYPE); // args
            asm.load(4, OBJECT_TYPE); // refArgs
            emitInvokeStatic(asm, CALL_HOST_FUNCTION_WITH_REFS);
            asm.areturn(OBJECT_TYPE);
        }

        // throw new InvalidException("unknown function " + funcId);
        asm.mark(defaultLabel);
        asm.load(2, INT_TYPE);
        emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION);
        asm.athrow();
    }

    // implements the body of:
    // public static long[] call_xxx(Instance instance, Memory memory, long[] args, Object[]
    // refArgs)
    private void compileCallFunction(int funcId, FunctionType type, InstructionAdapter asm) {

        if (hasTooManyParameters(type)) {
            asm.load(2, LONG_ARRAY_TYPE);
        } else {
            // unbox the arguments from long[] (and Object[] refArgs for GC refs)
            for (int i = 0; i < type.params().size(); i++) {
                var param = type.params().get(i);
                if (param.isObjectRef()) {
                    // GC ref args: load from Object[] refArgs (null-safe)
                    asm.load(3, OBJECT_TYPE); // refArgs
                    Label hasRefArgs = new Label();
                    Label refDone = new Label();
                    asm.dup();
                    asm.ifnonnull(hasRefArgs);
                    asm.pop();
                    asm.aconst(null); // default null for missing refArgs
                    asm.goTo(refDone);
                    asm.mark(hasRefArgs);
                    asm.iconst(i);
                    asm.aload(OBJECT_TYPE);
                    asm.mark(refDone);
                } else {
                    asm.load(2, OBJECT_TYPE);
                    asm.iconst(i);
                    asm.aload(LONG_TYPE);
                    emitLongToJvm(asm, param);
                }
            }
        }

        asm.load(1, OBJECT_TYPE);
        asm.load(0, OBJECT_TYPE);

        emitInvokeFunction(
                asm,
                internalClassName(classNameForFuncGroup(className, funcId)),
                methodName(funcId),
                type);

        // box the result into long[]
        Class<?> returnType = jvmReturnType(type);
        if (returnType == void.class) {
            asm.aconst(null);
        } else if (returnType == Object.class) {
            // GC ref return: discard the Object result and return a dummy long[]{0}.
            // GC ref results should be accessed via callWithRefs() which goes through the
            // interpreter path and handles Object results natively.
            asm.pop();
            asm.iconst(1);
            asm.newarray(LONG_TYPE);
        } else if (returnType != long[].class && returnType != Object[].class) {
            emitJvmToLong(asm, type.returns().get(0));
            asm.store(4, LONG_TYPE);
            asm.iconst(1);
            asm.newarray(LONG_TYPE);
            asm.dup();
            asm.iconst(0);
            asm.load(4, LONG_TYPE);
            asm.astore(LONG_TYPE);
        }
        if (returnType == Object[].class) {
            // Multi-value with GC refs: the internal function returns Object[],
            // but the bridge must return long[]. Convert: longs for numerics, 0L for refs.
            int objArraySlot = 4;
            asm.store(objArraySlot, OBJECT_TYPE); // save Object[]
            asm.iconst(type.returns().size());
            asm.newarray(LONG_TYPE); // create long[]
            for (int i = 0; i < type.returns().size(); i++) {
                var retType = type.returns().get(i);
                asm.dup();
                asm.iconst(i);
                if (retType.isObjectRef()) {
                    asm.lconst(0L);
                } else {
                    asm.load(objArraySlot, OBJECT_TYPE);
                    asm.iconst(i);
                    asm.aload(OBJECT_TYPE); // get from Object[]
                    asm.checkcast(Type.getType(Long.class));
                    asm.invokevirtual("java/lang/Long", "longValue", "()J", false);
                }
                asm.astore(LONG_TYPE);
            }
        }
        // For long[] multi-value returns, leave as-is
        asm.areturn(OBJECT_TYPE);
    }

    // implements the body of:
    // public static CallResult callWithRefs_xxx(Instance instance, Memory memory, long[] args,
    //     Object[] refArgs)
    // Same parameter unboxing as call_xxx, but returns CallResult with split long[]+Object[]
    private void compileCallWithRefsFunction(
            int funcId, FunctionType type, InstructionAdapter asm) {

        if (hasTooManyParameters(type)) {
            asm.load(2, LONG_ARRAY_TYPE);
        } else {
            // unbox the arguments from long[] (and Object[] refArgs for GC refs)
            for (int i = 0; i < type.params().size(); i++) {
                var param = type.params().get(i);
                if (param.isObjectRef()) {
                    // GC ref args: load from Object[] refArgs (null-safe)
                    asm.load(3, OBJECT_TYPE); // refArgs
                    Label hasRefArgs = new Label();
                    Label refDone = new Label();
                    asm.dup();
                    asm.ifnonnull(hasRefArgs);
                    asm.pop();
                    asm.aconst(null); // default null for missing refArgs
                    asm.goTo(refDone);
                    asm.mark(hasRefArgs);
                    asm.iconst(i);
                    asm.aload(OBJECT_TYPE);
                    asm.mark(refDone);
                } else {
                    asm.load(2, OBJECT_TYPE);
                    asm.iconst(i);
                    asm.aload(LONG_TYPE);
                    emitLongToJvm(asm, param);
                }
            }
        }

        asm.load(1, OBJECT_TYPE);
        asm.load(0, OBJECT_TYPE);

        emitInvokeFunction(
                asm,
                internalClassName(classNameForFuncGroup(className, funcId)),
                methodName(funcId),
                type);

        // Build CallResult from the function's JVM return value
        Class<?> returnType = jvmReturnType(type);
        if (returnType == void.class) {
            // new CallResult(null, null)
            asm.aconst(null);
            asm.aconst(null);
            asm.invokestatic(CALL_RESULT_INTERNAL_NAME, "of", CALL_RESULT_OF_DESCRIPTOR, false);
        } else if (returnType == Object.class) {
            // Single Object ref return: new CallResult(null, new Object[]{result})
            int refSlot = 4;
            asm.store(refSlot, OBJECT_TYPE); // save the Object result
            asm.aconst(null); // longs = null
            asm.iconst(1);
            asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            asm.dup();
            asm.iconst(0);
            asm.load(refSlot, OBJECT_TYPE);
            asm.astore(OBJECT_TYPE);
            asm.invokestatic(CALL_RESULT_INTERNAL_NAME, "of", CALL_RESULT_OF_DESCRIPTOR, false);
        } else if (returnType == Object[].class) {
            // Multi-value with GC refs: split Object[] into positional long[] + Object[]
            // Both arrays use type.returns().size() as length, matching the interpreter
            // convention where index i corresponds to result position i.
            int totalResults = type.returns().size();
            int objArraySlot = 4;
            asm.store(objArraySlot, OBJECT_TYPE); // save Object[]

            // Build long[totalResults] for numeric values (0L for ref slots)
            int longArraySlot = 5;
            asm.iconst(totalResults);
            asm.newarray(LONG_TYPE);
            for (int i = 0; i < totalResults; i++) {
                var retType = type.returns().get(i);
                if (!retType.isObjectRef()) {
                    asm.dup();
                    asm.iconst(i);
                    asm.load(objArraySlot, OBJECT_TYPE);
                    asm.iconst(i);
                    asm.aload(OBJECT_TYPE);
                    asm.checkcast(Type.getType(Long.class));
                    asm.invokevirtual("java/lang/Long", "longValue", "()J", false);
                    asm.astore(LONG_TYPE);
                }
            }
            asm.store(longArraySlot, OBJECT_TYPE);

            // Build Object[totalResults] for ref values (null for numeric slots)
            int refArraySlot = 6;
            asm.iconst(totalResults);
            asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < totalResults; i++) {
                var retType = type.returns().get(i);
                if (retType.isObjectRef()) {
                    asm.dup();
                    asm.iconst(i);
                    asm.load(objArraySlot, OBJECT_TYPE);
                    asm.iconst(i);
                    asm.aload(OBJECT_TYPE);
                    asm.astore(OBJECT_TYPE);
                }
            }
            asm.store(refArraySlot, OBJECT_TYPE);

            // new CallResult(longs, refs)
            asm.load(longArraySlot, OBJECT_TYPE);
            asm.load(refArraySlot, OBJECT_TYPE);
            asm.invokestatic(CALL_RESULT_INTERNAL_NAME, "of", CALL_RESULT_OF_DESCRIPTOR, false);
        } else if (returnType == long[].class) {
            // Multi-value, no Object refs: new CallResult(longArray, null)
            int longArraySlot = 4;
            asm.store(longArraySlot, OBJECT_TYPE);
            asm.load(longArraySlot, OBJECT_TYPE);
            asm.aconst(null);
            asm.invokestatic(CALL_RESULT_INTERNAL_NAME, "of", CALL_RESULT_OF_DESCRIPTOR, false);
        } else {
            // Single numeric return: new CallResult(new long[]{value}, null)
            emitJvmToLong(asm, type.returns().get(0));
            int longSlot = 4;
            asm.store(longSlot, LONG_TYPE);
            asm.iconst(1);
            asm.newarray(LONG_TYPE);
            asm.dup();
            asm.iconst(0);
            asm.load(longSlot, LONG_TYPE);
            asm.astore(LONG_TYPE);
            asm.aconst(null);
            asm.invokestatic(CALL_RESULT_INTERNAL_NAME, "of", CALL_RESULT_OF_DESCRIPTOR, false);
        }
        asm.areturn(OBJECT_TYPE);
    }

    // implements the body of:
    // public static <TypeR> call_indirect_xxx(<TypeN> argN...,
    //      int funcTableIdx, int tableIdx, Memory memory, Instance instance)
    private void compileCallIndirect(
            String internalClassName, int typeId, FunctionType type, InstructionAdapter asm) {

        int slots = type.params().stream().mapToInt(CompilerUtil::slotCount).sum();
        if (hasTooManyParameters(type)) {
            slots = 1; // for long[]
        }

        List<Integer> validIds = new ArrayList<>();
        for (int i = 0; i < functionTypes.size(); i++) {
            if (isFuncTypeMatch(typeId, i, type)) {
                validIds.add(i);
            }
        }
        Label invalid = new Label();

        // extra params...
        int funcTableIdx = slots;
        int tableIdx = slots + 1;
        int memory = slots + 2;
        int instance = slots + 3;

        // local vars
        int table = slots + 4;
        int funcId = slots + 5;
        int refInstance = slots + 6;

        emitInvokeStatic(asm, CHECK_INTERRUPTION);

        Label local = new Label();
        Label other = new Label();

        boolean hasCallRef = callRefTypeIds.contains(typeId);

        if (hasCallRef) {
            // For call_ref (tableIdx == -1), funcTableIdx IS the funcId directly
            asm.load(tableIdx, INT_TYPE);
            asm.iconst(-1);
            Label notCallRef = new Label();
            asm.ificmpne(notCallRef);

            // call_ref path: funcTableIdx is the funcId, instance is local
            asm.load(funcTableIdx, INT_TYPE);
            asm.store(funcId, INT_TYPE);
            asm.goTo(local);

            // call_indirect path: look up through table
            asm.mark(notCallRef);
        }

        // TableInstance table = instance.table(tableIdx);
        asm.load(instance, OBJECT_TYPE);
        asm.load(tableIdx, INT_TYPE);
        emitInvokeVirtual(asm, INSTANCE_TABLE);
        asm.store(table, OBJECT_TYPE);

        // int funcId = table.requiredRef(funcTableIdx);
        asm.load(table, OBJECT_TYPE);
        asm.load(funcTableIdx, INT_TYPE);
        emitInvokeVirtual(asm, TABLE_REQUIRED_REF);
        asm.store(funcId, INT_TYPE);

        // Instance refInstance = table.instance(funcTableIdx);
        asm.load(table, OBJECT_TYPE);
        asm.load(funcTableIdx, INT_TYPE);
        emitInvokeVirtual(asm, TABLE_INSTANCE);
        asm.store(refInstance, OBJECT_TYPE);

        // if (refInstance == null || refInstance == instance)
        asm.load(refInstance, OBJECT_TYPE);
        asm.ifnull(local);
        asm.load(refInstance, OBJECT_TYPE);
        asm.load(instance, OBJECT_TYPE);
        asm.ifacmpne(other);

        // local: call function in this module
        asm.mark(local);
        if (hasTooManyParameters(type)) {
            asm.load(0, LONG_ARRAY_TYPE);
        } else {
            int slot = 0;
            for (ValType param : type.params()) {
                asm.load(slot, asmType(param));
                slot += slotCount(param);
            }
        }
        asm.load(memory, OBJECT_TYPE);
        asm.load(instance, OBJECT_TYPE);

        // Can we fit the impl in a single method?
        if (validIds.size() <= MAX_CALL_INDIRECT_METHODS) {

            int[] keys = validIds.stream().mapToInt(x -> x).toArray();
            Label[] labels = validIds.stream().map(x -> new Label()).toArray(Label[]::new);

            asm.load(funcId, INT_TYPE);
            asm.lookupswitch(invalid, keys, labels);

            for (int i = 0; i < validIds.size(); i++) {
                // case 0:
                //    return func_0(a, b, memory, callerInstance);
                asm.mark(labels[i]);
                emitInvokeFunction(
                        asm,
                        classNameForFuncGroup(internalClassName, keys[i]),
                        methodName(keys[i]),
                        type);
                asm.areturn(getType(jvmReturnType(type)));
            }

            asm.mark(invalid);
            emitInvokeStatic(asm, THROW_INDIRECT_CALL_TYPE_MISMATCH);
            asm.athrow();

        } else {
            var applyParams =
                    rawMethodTypeFor(type)
                            .appendParameterTypes(Memory.class, Instance.class, int.class);

            // Best value that worked with the 50K small wasm functions
            var maxCallIndirectMethods = MAX_CALL_INDIRECT_METHODS << 2;
            loadChunkedClass(
                    functionTypes.size(),
                    maxCallIndirectMethods,
                    (collector, start, end, chunkSize) ->
                            compileExtraClass(
                                    collector,
                                    classNameForCallIndirect(this.className, typeId, start),
                                    (cw) -> {
                                        emitFunction(
                                                cw,
                                                "apply",
                                                applyParams,
                                                true,
                                                a ->
                                                        compileCallIndirectApply(
                                                                internalClassName,
                                                                typeId,
                                                                type,
                                                                a,
                                                                start,
                                                                end));
                                    }));

            assert Integer.bitCount(maxCallIndirectMethods) == 1; // power of two
            int shift = Integer.numberOfTrailingZeros(maxCallIndirectMethods);

            // switch (funcId >> shift)
            Label[] labels = new Label[((functionTypes.size() - 1) >> shift) + 1];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = new Label();
            }

            asm.load(funcId, INT_TYPE);
            asm.iconst(shift);
            asm.shr(INT_TYPE);
            asm.tableswitch(0, labels.length - 1, labels[0], labels);

            // invoke the method that we are about to generate
            for (int i = 0; i < labels.length; i++) {
                asm.mark(labels[i]);
                asm.load(funcId, INT_TYPE);
                asm.invokestatic(
                        classNameForCallIndirect(internalClassName, typeId, i << shift),
                        "apply",
                        applyParams.toMethodDescriptorString(),
                        false);
                asm.areturn(getType(jvmReturnType(type)));
            }
        }

        // other: call function in another module
        asm.mark(other);

        if (type.hasObjectRefParams() || type.hasObjectRefReturns()) {
            if (hasTooManyParameters(type)) {
                asm.load(0, LONG_ARRAY_TYPE);
                asm.aconst(null); // Object[] refArgs
            } else {
                emitBoxArgumentsWithRefs(asm, type.params());
                // stack: long[], Object[]
            }
            asm.load(instance, OBJECT_TYPE);
            asm.iconst(typeId);
            emitInvokeVirtual(asm, INSTANCE_GET_TYPE);
            asm.load(funcId, INT_TYPE);
            asm.load(refInstance, OBJECT_TYPE);

            emitInvokeStatic(asm, CALL_INDIRECT_WITH_REFS_CROSS_MODULE);
            // returns CallResult
            emitUnboxCallResult(type, asm);
        } else {
            if (hasTooManyParameters(type)) {
                asm.load(0, LONG_ARRAY_TYPE);
            } else {
                emitBoxArguments(asm, type.params());
            }
            asm.load(instance, OBJECT_TYPE);
            asm.iconst(typeId);
            emitInvokeVirtual(asm, INSTANCE_GET_TYPE);
            asm.load(funcId, INT_TYPE);
            asm.load(refInstance, OBJECT_TYPE);

            emitInvokeStatic(asm, CALL_INDIRECT_CROSS_MODULE);

            emitUnboxResult(type, asm);
        }
    }

    private void compileCallIndirectApply(
            String internalClassName,
            int typeId,
            FunctionType type,
            InstructionAdapter asm,
            int startFunc,
            int endFunc) {

        int slots = type.params().stream().mapToInt(CompilerUtil::slotCount).sum();
        if (hasTooManyParameters(type)) {
            slots = 1; // for long[]
        }

        // extra params...
        int memory = slots;
        int instance = slots + 1;
        int funcId = slots + 2;

        List<Integer> validIds = new ArrayList<>();
        for (int i = 0; i < functionTypes.size(); i++) {
            if (isFuncTypeMatch(typeId, i, type) && startFunc <= i && i < endFunc) {
                validIds.add(i);
            }
        }
        Label invalid = new Label();

        int[] keys = validIds.stream().mapToInt(x -> x).toArray();
        Label[] labels = validIds.stream().map(x -> new Label()).toArray(Label[]::new);

        // push the call args on to the stack...
        for (int i = 0; i < type.params().size(); i++) {
            asm.load(i, asmType(type.params().get(i)));
        }
        asm.load(memory, OBJECT_TYPE);
        asm.load(instance, OBJECT_TYPE);

        // switch (funcId)
        asm.load(funcId, INT_TYPE);
        asm.lookupswitch(invalid, keys, labels);

        for (int i = 0; i < validIds.size(); i++) {
            // case 0:
            //    return func_0(a, b, memory, callerInstance);
            asm.mark(labels[i]);
            emitInvokeFunction(
                    asm,
                    classNameForFuncGroup(internalClassName, keys[i]),
                    methodName(keys[i]),
                    type);
            asm.areturn(getType(jvmReturnType(type)));
            asm.areturn(OBJECT_TYPE);
        }

        // throw new InvalidException("unknown function " + funcId);
        asm.mark(invalid);

        asm.load(funcId, INT_TYPE);
        emitInvokeStatic(asm, THROW_UNKNOWN_FUNCTION);
        asm.athrow();
    }

    // implements the body of:
    // public static <TypeR> func_xx(<TypeN> argN..., Memory memory, Instance instance)
    private static void compileHostFunction(int funcId, FunctionType type, InstructionAdapter asm) {

        int slot = type.params().stream().mapToInt(CompilerUtil::slotCount).sum();
        if (type.hasObjectRefParams() || type.hasObjectRefReturns()) {
            asm.load(slot + 1, OBJECT_TYPE); // instance
            asm.iconst(funcId);
            emitBoxArgumentsWithRefs(asm, type.params());
            // stack: instance, funcId, long[], Object[]
            emitInvokeStatic(asm, CALL_HOST_FUNCTION_WITH_REFS);
            // returns CallResult
            emitUnboxCallResult(type, asm);
        } else {
            asm.load(slot + 1, OBJECT_TYPE); // instance
            asm.iconst(funcId);
            emitBoxArguments(asm, type.params());
            emitInvokeStatic(asm, CALL_HOST_FUNCTION);
            emitUnboxResult(type, asm);
        }
    }

    private static void emitBoxArguments(InstructionAdapter asm, List<ValType> types) {
        int slot = 0;
        asm.iconst(types.size());
        asm.newarray(LONG_TYPE);
        for (int i = 0; i < types.size(); i++) {
            asm.dup();
            asm.iconst(i);
            ValType valType = types.get(i);
            asm.load(slot, asmType(valType));
            if (valType.isObjectRef()) {
                asm.visitInsn(Opcodes.POP);
                asm.lconst(0L);
            } else {
                emitJvmToLong(asm, valType);
            }
            asm.astore(LONG_TYPE);
            slot += slotCount(valType);
        }
    }

    /**
     * Boxes JVM locals into a single Object[] array.
     * Numeric values are boxed as Long, Object refs stay as Object.
     * Used for multi-value returns (value_xxx methods) when any return is a GC ref.
     */
    private static void emitBoxArgumentsAsObjectArray(InstructionAdapter asm, List<ValType> types) {
        int slot = 0;
        asm.iconst(types.size());
        asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < types.size(); i++) {
            asm.dup();
            asm.iconst(i);
            ValType valType = types.get(i);
            asm.load(slot, asmType(valType));
            if (valType.isObjectRef()) {
                // Object ref: already on stack as Object
            } else {
                // Numeric: convert to long then box into Long
                emitJvmToLong(asm, valType);
                asm.invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            }
            asm.astore(OBJECT_TYPE);
            slot += slotCount(valType);
        }
    }

    /**
     * Boxes JVM locals into dual long[] + Object[] arrays.
     * After this method, the stack has: [..., long[], Object[]]
     * If no values are Object refs, Object[] will be null (zero overhead).
     */
    private static void emitBoxArgumentsWithRefs(InstructionAdapter asm, List<ValType> types) {
        boolean hasObjectRefs = types.stream().anyMatch(ValType::isObjectRef);

        // Build long[]
        int slot = 0;
        asm.iconst(types.size());
        asm.newarray(LONG_TYPE);
        for (int i = 0; i < types.size(); i++) {
            asm.dup();
            asm.iconst(i);
            ValType valType = types.get(i);
            asm.load(slot, asmType(valType));
            if (valType.isObjectRef()) {
                asm.visitInsn(Opcodes.POP);
                asm.lconst(0L);
            } else {
                emitJvmToLong(asm, valType);
            }
            asm.astore(LONG_TYPE);
            slot += slotCount(valType);
        }

        // Build Object[] (or push null)
        if (hasObjectRefs) {
            asm.iconst(types.size());
            asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            slot = 0;
            for (int i = 0; i < types.size(); i++) {
                ValType valType = types.get(i);
                if (valType.isObjectRef()) {
                    asm.dup();
                    asm.iconst(i);
                    asm.load(slot, asmType(valType));
                    asm.astore(OBJECT_TYPE);
                }
                slot += slotCount(valType);
            }
        } else {
            asm.aconst(null);
        }
    }

    private static void emitUnboxResult(FunctionType type, InstructionAdapter asm) {
        Class<?> returnType = jvmReturnType(type);
        if (returnType == void.class) {
            asm.areturn(VOID_TYPE);
        } else if (returnType == long[].class || returnType == Object[].class) {
            asm.areturn(OBJECT_TYPE);
        } else if (returnType == Object.class) {
            // Single Object return from cross-module call that returns long[]
            // The Object result is in the long[] as 0, can't extract it
            // Callers with Object refs should use emitUnboxCallResult instead
            asm.visitInsn(Opcodes.POP); // pop the long[] result
            asm.aconst(null);
            asm.areturn(OBJECT_TYPE);
        } else {
            // unbox the result from long[0]
            asm.iconst(0);
            asm.aload(LONG_TYPE);
            emitLongToJvm(asm, type.returns().get(0));
            asm.areturn(getType(returnType));
        }
    }

    /**
     * Unboxes a CallResult into the proper JVM return value.
     * Used for cross-module/host calls that go through callWithRefs / callHostFunctionWithRefs.
     * The CallResult has .longs() for numerics and .refs() for Object refs.
     */
    private static void emitUnboxCallResult(FunctionType type, InstructionAdapter asm) {
        Class<?> returnType = jvmReturnType(type);
        if (returnType == void.class) {
            asm.pop(); // discard CallResult
            asm.areturn(VOID_TYPE);
        } else if (returnType == Object.class) {
            // Single Object return: extract from CallResult.refResult(0)
            asm.iconst(0);
            asm.invokevirtual(
                    CALL_RESULT_INTERNAL_NAME, "refResult", "(I)Ljava/lang/Object;", false);
            asm.areturn(OBJECT_TYPE);
        } else if (returnType == long[].class) {
            // Multi-value, no Object refs: extract longs()
            asm.invokevirtual(CALL_RESULT_INTERNAL_NAME, "longs", "()[J", false);
            asm.areturn(OBJECT_TYPE);
        } else if (returnType == Object[].class) {
            // Multi-value with Object refs: build Object[] from CallResult
            // store CallResult in a temp
            int crSlot = type.params().stream().mapToInt(CompilerUtil::slotCount).sum() + 2;
            asm.store(crSlot, OBJECT_TYPE);

            asm.iconst(type.returns().size());
            asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

            int slot = 0;
            for (int i = 0; i < type.returns().size(); i++) {
                asm.dup();
                asm.iconst(i);
                ValType retType = type.returns().get(i);
                if (retType.isObjectRef()) {
                    asm.load(crSlot, OBJECT_TYPE);
                    asm.iconst(slot);
                    asm.invokevirtual(
                            CALL_RESULT_INTERNAL_NAME, "refResult", "(I)Ljava/lang/Object;", false);
                    slot++;
                } else if (retType.equals(ValType.V128)) {
                    asm.load(crSlot, OBJECT_TYPE);
                    asm.iconst(slot);
                    asm.invokevirtual(CALL_RESULT_INTERNAL_NAME, "longResult", "(I)J", false);
                    asm.invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                    slot += 2;
                } else {
                    asm.load(crSlot, OBJECT_TYPE);
                    asm.iconst(slot);
                    asm.invokevirtual(CALL_RESULT_INTERNAL_NAME, "longResult", "(I)J", false);
                    asm.invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                    slot++;
                }
                asm.astore(OBJECT_TYPE);
            }
            asm.areturn(OBJECT_TYPE);
        } else {
            // Single numeric return: extract from CallResult.longResult(0)
            asm.iconst(0);
            asm.invokevirtual(CALL_RESULT_INTERNAL_NAME, "longResult", "(I)J", false);
            emitLongToJvm(asm, type.returns().get(0));
            asm.areturn(getType(returnType));
        }
    }

    // implements the body of:
    // public static <TypeR> func_xxx(<TypeN> ArgN..., Memory memory, Instance instance)
    private void compileFunction(
            ClassVisitor classWriter,
            String internalClassName,
            int funcId,
            FunctionType type,
            FunctionBody body,
            InstructionAdapter asm) {

        if (interpretedFunctions.contains(funcId)) {

            var slots = 0;
            if (type.hasObjectRefParams() || type.hasObjectRefReturns()) {
                // WithRefs path: use dual long[] + Object[] boxing
                if (hasTooManyParameters(type)) {
                    asm.load(0, LONG_ARRAY_TYPE);
                    asm.aconst(null); // Object[] refArgs
                    slots = 1;
                } else {
                    emitBoxArgumentsWithRefs(asm, type.params());
                    slots = type.params().stream().mapToInt(CompilerUtil::slotCount).sum();
                }
                var refInstance = slots + 1;
                asm.iconst(funcId);
                asm.load(refInstance, OBJECT_TYPE);
                emitInvokeStatic(asm, CALL_INDIRECT_ON_INTERPRETER_WITH_REFS);
                // returns CallResult
                emitUnboxCallResult(type, asm);
            } else {
                if (hasTooManyParameters(type)) {
                    asm.load(0, LONG_ARRAY_TYPE);
                    slots = 1;
                } else {
                    emitBoxArguments(asm, type.params());
                    slots = type.params().stream().mapToInt(CompilerUtil::slotCount).sum();
                }
                var refInstance = slots + 1;
                asm.iconst(funcId);
                asm.load(refInstance, OBJECT_TYPE);
                emitInvokeStatic(asm, CALL_INDIRECT_ON_INTERPRETER);
                emitUnboxResult(type, asm);
            }
            return;
        }

        var analysis = analyzer.analyze(funcId);
        List<CompilerInstruction> instructions = analysis.instructions();
        var ctx = newContext(funcId, type, body, analysis, internalClassName);

        int[] trampolineStarts = trampolineChunkStarts.get(funcId);
        if (trampolineStarts != null) {
            emitTrampolineFunction(
                    classWriter,
                    internalClassName,
                    funcId,
                    type,
                    body,
                    instructions,
                    analysis,
                    ctx,
                    trampolineStarts,
                    asm);
            return;
        }

        int[] cuts = functionChunkBoundaries.get(funcId);
        if (cuts != null) {
            emitChunkedFunction(
                    classWriter,
                    internalClassName,
                    funcId,
                    type,
                    body,
                    instructions,
                    ctx,
                    cuts,
                    asm);
            return;
        }

        emitFunctionBody(ctx, type, body, instructions, asm);
    }

    private Context newContext(
            int funcId,
            FunctionType type,
            FunctionBody body,
            WasmAnalyzer.AnalysisResult analysis,
            String internalClassName) {
        return newContext(funcId, type, body, analysis, internalClassName, false);
    }

    private Context newContext(
            int funcId,
            FunctionType type,
            FunctionBody body,
            WasmAnalyzer.AnalysisResult analysis,
            String internalClassName,
            boolean forceFrameLayout) {
        return new Context(
                module,
                internalClassName,
                maxFunctionsPerClass,
                analyzer.globalTypes(),
                functionTypes,
                funcId,
                type,
                body,
                tailCallFunctions,
                tailCallTypes,
                useBridgeClasses ? callIndirectClassResolver : typeId -> internalClassName,
                analysis.maxTempSlots(),
                this::methodName,
                forceFrameLayout);
    }

    // Estimates JVM Code-attribute size by counting per-instruction encoding lengths
    // per the class file spec. Approximate (e.g. always assumes the wider constant-pool
    // index / offset form where an instruction has a narrow and wide encoding), which
    // is fine here: this only ever feeds a diagnostic warning, never a compile decision.
    // Standalone rather than asm-util's CodeSizeEvaluator because this build's asm-util
    // artifact has been minimized and no longer includes it.
    private static class CodeSizeCounter extends MethodVisitor {
        int size;

        CodeSizeCounter() {
            this(null);
        }

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
    // translates the flattened, already-lowered instruction stream to JVM bytecode.
    private void emitFunctionBody(
            Context ctx,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> instructions,
            InstructionAdapter asm) {

        int localsCount = type.params().size();
        if (hasTooManyParameters(type)) {
            // unbox the arguments from long[]
            for (int i = 0; i < type.params().size(); i++) {
                var param = type.params().get(i);
                if (param.isObjectRef()) {
                    // Object refs can't be stored in long[] — use null as default
                    asm.aconst(null);
                } else {
                    asm.load(0, OBJECT_TYPE);
                    asm.iconst(i);
                    asm.aload(LONG_TYPE);
                    emitLongToJvm(asm, param);
                }
                asm.store(ctx.localSlotIndex(i), asmType(param));
            }
            // since we just converted the arguments to long[].
            localsCount = 1;
        }

        // initialize local variables to their default values
        emitLocalsDefaultInit(ctx, type, body, asm);

        // allocate labels for all label targets
        Map<Long, Label> labels = new HashMap<>();
        for (var ins : instructions) {
            for (long target : ins.labelTargets()) {
                labels.put(target, new Label());
            }
        }

        // track targets to detect backward jumps
        Set<Long> visitedTargets = new HashSet<>();

        // compile the function body
        for (CompilerInstruction ins : instructions) {
            emitInstruction(ctx, labels, visitedTargets, ins, asm);
        }
    }

    // Initializes declared (non-parameter) Wasm locals to their default values. Used
    // both by normal function emission and by chunk 0 of a split function -- chunks
    // 1..N-1 never run this, since their locals arrive as incoming call arguments.
    private void emitLocalsDefaultInit(
            Context ctx, FunctionType type, FunctionBody body, InstructionAdapter asm) {
        int totalLocals = type.params().size() + body.localTypes().size();
        for (int i = type.params().size(); i < totalLocals; i++) {
            var localType = localType(type, body, i);
            var defVal = defaultValue(localType);
            if (defVal == null) {
                // GC ref types: default to null (Object)
                asm.aconst(null);
            } else {
                asm.visitLdcInsn(defVal);
            }
            asm.store(ctx.localSlotIndex(i), asmType(localType));
        }
    }

    // Emits the JVM bytecode for a single already-lowered instruction. Factored out
    // of emitFunctionBody so the oversized-function split planner (below) can drive
    // the exact same emission logic through a throwaway buffer to measure sizes and
    // analyze operand-stack depth, without duplicating the opcode dispatch.
    private void emitInstruction(
            Context ctx,
            Map<Long, Label> labels,
            Set<Long> visitedTargets,
            CompilerInstruction ins,
            InstructionAdapter asm) {
        switch (ins.opcode()) {
            case LABEL:
                Label label = labels.get(ins.operand(0));
                if (label != null) {
                    asm.mark(label);
                    visitedTargets.add(ins.operand(0));
                }
                break;
            case GOTO:
                if (visitedTargets.contains(ins.operand(0))) {
                    emitInvokeStatic(asm, CHECK_INTERRUPTION);
                }
                asm.goTo(labels.get(ins.operand(0)));
                break;
            case IFEQ:
                if (visitedTargets.contains(ins.operand(0))) {
                    throw new WasmEngineException("Unexpected backward jump");
                }
                asm.ifeq(labels.get(ins.operand(0)));
                break;
            case IFNE:
                if (visitedTargets.contains(ins.operand(0))) {
                    Label skip = new Label();
                    asm.ifeq(skip);
                    emitInvokeStatic(asm, CHECK_INTERRUPTION);
                    asm.goTo(labels.get(ins.operand(0)));
                    asm.mark(skip);

                } else {
                    asm.ifne(labels.get(ins.operand(0)));
                }
                break;
            case SWITCH:
                if (ins.operands().anyMatch(visitedTargets::contains)) {
                    emitInvokeStatic(asm, CHECK_INTERRUPTION);
                }
                // table switch using the last entry of the table as the default
                Label[] table = new Label[ins.operandCount() - 1];
                for (int i = 0; i < table.length; i++) {
                    table[i] = labels.get(ins.operand(i));
                }
                Label defaultLabel = labels.get(ins.operand(table.length));
                asm.tableswitch(0, table.length - 1, defaultLabel, table);
                break;
            case TRY_CATCH_BLOCK:
                asm.visitTryCatchBlock(
                        labels.get(ins.operand(0)),
                        labels.get(ins.operand(1)),
                        labels.get(ins.operand(2)),
                        getInternalName(WasmException.class));
                break;
            default:
                var emitter = EMITTERS.get(ins.opcode());
                if (emitter == null) {
                    throw new WasmEngineException("Unhandled opcode: " + ins.opcode());
                }
                emitter.emit(ctx, ins, asm);
        }
    }

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
    private void emitChunkedFunction(
            ClassVisitor classWriter,
            String internalClassName,
            int funcId,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> instructions,
            Context ctx,
            int[] cuts,
            InstructionAdapter asm) {

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
        String funcName = methodName(funcId);
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
    private void emitChunkAwareInstructions(
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
    private void emitChunkAwareSwitch(
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
    // jumping. See TRAMPOLINE_SPLIT_ENABLED for why this exists.
    private void emitTrampolineFunction(
            ClassVisitor classWriter,
            String internalClassName,
            int funcId,
            FunctionType type,
            FunctionBody body,
            List<CompilerInstruction> instructions,
            WasmAnalyzer.AnalysisResult analysis,
            Context entryCtx,
            int[] starts,
            InstructionAdapter asm) {

        int totalLocals = type.params().size() + body.localTypes().size();
        int pcIndex = totalLocals; // the frame slot after all locals holds the PC
        int frameLength = totalLocals + 1;
        int numChunks = starts.length;
        String funcName = methodName(funcId);
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
        Context chunkCtx = newContext(funcId, type, body, analysis, internalClassName, true);

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
                                    totalLocals,
                                    returnAsmType,
                                    chunkAsm));
        }
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

    private void emitTrampolineChunk(
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
            int totalLocals,
            Type returnAsmType,
            InstructionAdapter asm) {

        // Default the PC to DONE, so an ordinary `return` inside this slice needs no
        // special handling at all -- it just areturns, and the driver sees DONE.
        asm.load(0, LONG_ARRAY_TYPE);
        asm.iconst(pcIndex);
        asm.lconst(TRAMPOLINE_PC_DONE);
        asm.astore(LONG_TYPE);

        // unbox every local out of the frame into the JVM slots Context assigned
        for (int i = 0; i < totalLocals; i++) {
            var localType = localType(type, body, i);
            asm.load(0, LONG_ARRAY_TYPE);
            asm.iconst(i);
            asm.aload(LONG_TYPE);
            emitLongToJvm(asm, localType);
            asm.store(ctx.localSlotIndex(i), asmType(localType));
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
                    ins,
                    asm);
        }

        // Falling off the end of a chunk continues into the next one. For the last
        // chunk this is unreachable (the body ends in a return), but emitting a
        // well-formed DONE keeps the method verifiable either way.
        asm.iconst(chunkIdx + 1 < numChunks ? chunkIdx + 1 : TRAMPOLINE_PC_DONE);
        asm.store(pcTempSlot, INT_TYPE);
        asm.goTo(sharedExit);

        for (Map.Entry<Integer, Label> stub : switchStubs.entrySet()) {
            asm.mark(stub.getValue());
            asm.iconst(stub.getKey());
            asm.store(pcTempSlot, INT_TYPE);
            asm.goTo(sharedExit);
        }

        asm.mark(sharedExit);
        for (int i = 0; i < totalLocals; i++) {
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
    private void emitTrampolineInstruction(
            Context ctx,
            Map<Long, Label> labels,
            Map<Long, Integer> labelDefPosition,
            Set<Long> visitedTargets,
            int[] starts,
            int chunkIdx,
            Label sharedExit,
            int pcTempSlot,
            Map<Integer, Label> switchStubs,
            CompilerInstruction ins,
            InstructionAdapter asm) {

        switch (ins.opcode()) {
            case GOTO:
                {
                    int target = trampolineTargetChunk(ins.operand(0), labelDefPosition, starts);
                    if (target == chunkIdx) {
                        break; // handled by the ordinary emitter below
                    }
                    emitTrampolineTransition(target, pcTempSlot, sharedExit, asm);
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
                    emitTrampolineTransition(target, pcTempSlot, sharedExit, asm);
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
                    emitTrampolineTransition(target, pcTempSlot, sharedExit, asm);
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

    private static void emitTrampolineTransition(
            int targetChunk, int pcTempSlot, Label sharedExit, InstructionAdapter asm) {
        asm.iconst(targetChunk);
        asm.store(pcTempSlot, INT_TYPE);
        asm.goTo(sharedExit);
    }

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
    private void emitChunkCall(
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
