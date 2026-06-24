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
import static run.endive.compiler.internal.CompilerUtil.jvmReturnType;
import static run.endive.compiler.internal.CompilerUtil.localType;
import static run.endive.compiler.internal.CompilerUtil.methodNameForFunc;
import static run.endive.compiler.internal.CompilerUtil.methodTypeFor;
import static run.endive.compiler.internal.CompilerUtil.rawMethodTypeFor;
import static run.endive.compiler.internal.CompilerUtil.slotCount;
import static run.endive.compiler.internal.CompilerUtil.valueMethodName;
import static run.endive.compiler.internal.CompilerUtil.valueMethodType;
import static run.endive.compiler.internal.EmitterMap.EMITTERS;
import static run.endive.compiler.internal.ShadedRefs.AOT_INTERPRETER_MACHINE_CALL;
import static run.endive.compiler.internal.ShadedRefs.CALL_HOST_FUNCTION;
import static run.endive.compiler.internal.ShadedRefs.CALL_HOST_FUNCTION_WITH_REFS;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT_ON_INTERPRETER;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT_ON_INTERPRETER_WITH_REFS;
import static run.endive.compiler.internal.ShadedRefs.CALL_INDIRECT_WITH_REFS;
import static run.endive.compiler.internal.ShadedRefs.CHECK_INTERRUPTION;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.objectweb.asm.ClassTooLargeException;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import run.endive.compiler.InterpreterFallback;
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
    private final Set<Integer> callRefTypeIds;
    private final boolean[] tailCallFunctions;
    private final boolean[] tailCallTypes;
    private final boolean moduleHasTailCalls;
    private final boolean moduleHasObjectRefs;
    private boolean useBridgeClasses;
    private IntFunction<String> callIndirectClassResolver;

    private Compiler(
            WasmModule module,
            String className,
            int maxFunctionsPerClass,
            InterpreterFallback interpreterFallback,
            Set<Integer> interpretedFunctions,
            Supplier<ClassCollector> classCollectorFactory) {
        this.className = requireNonNull(className, "className");
        this.module = requireNonNull(module, "module");
        this.analyzer = new WasmAnalyzer(module);
        this.functionImports = module.importSection().count(ExternalType.FUNCTION);
        this.classCollectorFactory = classCollectorFactory;
        this.collector = classCollectorFactory.get();

        if (interpretedFunctions == null || interpretedFunctions.isEmpty()) {
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

        this.functionTypes = analyzer.functionTypes();
        this.callRefTypeIds = collectCallRefTypeIds();
        this.tailCallFunctions = analyzer.tailCallFunctions();
        this.tailCallTypes = analyzer.tailCallTypes();
        this.moduleHasTailCalls = analyzer.hasTailCalls();
        this.moduleHasObjectRefs =
                this.functionTypes.stream()
                        .anyMatch(ft -> ft.hasObjectRefParams() || ft.hasObjectRefReturns());
        this.maxFunctionsPerClass = maxFunctionsPerClass;
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
                    classCollectorFactory);
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
                if (methodName.startsWith("func_")) {
                    // Add the method to interpreted function list... and try again.
                    var funcId = Integer.parseInt(methodName.substring("func_".length()));

                    String functionDescription = "WASM function index: " + funcId;
                    if (module.nameSection() != null) {
                        String name = module.nameSection().nameOfFunction(funcId);
                        if (name != null) {
                            functionDescription += String.format(" (name: %s)", name);
                        }
                    }

                    switch (interpreterFallback) {
                        case SILENT:
                            break;
                        case WARN:
                            System.err.println(
                                    "Warning: using interpreted mode for " + functionDescription);
                            break;
                        case FAIL:
                            throw new WasmEngineException(
                                    "WASM function size exceeds the Java method size limits and"
                                        + " cannot be compiled to Java bytecode. It can only be run"
                                        + " in the interpreter. Either reduce the size of the"
                                        + " function or enable the interpreter fallback mode: "
                                            + functionDescription,
                                    e);
                    }

                    interpretedFunctions.add(funcId);
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
                FunctionBody body = null;
                try {
                    int funcId = i;
                    var type = functionTypes.get(funcId);

                    // is it an import function?
                    if (i < functionImports) {
                        emitFunction(
                                classWriter,
                                methodNameForFunc(funcId),
                                methodTypeFor(type),
                                true,
                                asm -> compileHostFunction(funcId, type, asm));

                    } else {
                        body = module.codeSection().getFunctionBody(i - functionImports);
                        var bodyCopy = body;

                        emitFunction(
                                classWriter,
                                methodNameForFunc(funcId),
                                methodTypeFor(type),
                                true,
                                asm ->
                                        compileFunction(
                                                internalClassName, funcId, type, bodyCopy, asm));

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
                    throw handleMethodTooLarge(e, module);
                }
            }
        };
    }

    private byte[] compileClass() {
        var internalClassName = internalClassName(className);

        ClassWriter binaryWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
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
            throw handleMethodTooLarge(e, module);
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

    private static RuntimeException handleMethodTooLarge(
            MethodTooLargeException e, WasmModule module) {
        String name = e.getMethodName();
        if (name.startsWith("func_") && module.nameSection() != null) {
            int funcId = Integer.parseInt(name.split("_", -1)[1]);
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
            asm.anew(Type.getType(CallResult.class));
            asm.dup();
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
            asm.invokespecial(
                    CALL_RESULT_INTERNAL_NAME,
                    "<init>",
                    Type.getMethodDescriptor(
                            VOID_TYPE, LONG_ARRAY_TYPE, Type.getType(Object[].class)),
                    false);
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
        ClassWriter binaryWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
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
        ClassWriter binaryWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
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
                // Wrap long[] in CallResult
                int resultSlot = 5;
                asm.store(resultSlot, OBJECT_TYPE);
                asm.anew(Type.getType(CallResult.class));
                asm.dup();
                asm.load(resultSlot, OBJECT_TYPE);
                asm.aconst(null);
                asm.invokespecial(
                        CALL_RESULT_INTERNAL_NAME,
                        "<init>",
                        Type.getMethodDescriptor(
                                VOID_TYPE, LONG_ARRAY_TYPE, Type.getType(Object[].class)),
                        false);
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
                asm, internalClassName(classNameForFuncGroup(className, funcId)), funcId, type);

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
                asm, internalClassName(classNameForFuncGroup(className, funcId)), funcId, type);

        // Build CallResult from the function's JVM return value
        Class<?> returnType = jvmReturnType(type);
        if (returnType == void.class) {
            // new CallResult(null, null)
            asm.anew(Type.getType(CallResult.class));
            asm.dup();
            asm.aconst(null);
            asm.aconst(null);
            asm.invokespecial(
                    CALL_RESULT_INTERNAL_NAME,
                    "<init>",
                    Type.getMethodDescriptor(
                            VOID_TYPE, LONG_ARRAY_TYPE, Type.getType(Object[].class)),
                    false);
        } else if (returnType == Object.class) {
            // Single Object ref return: new CallResult(null, new Object[]{result})
            int refSlot = 4;
            asm.store(refSlot, OBJECT_TYPE); // save the Object result
            asm.anew(Type.getType(CallResult.class));
            asm.dup();
            asm.aconst(null); // longs = null
            asm.iconst(1);
            asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            asm.dup();
            asm.iconst(0);
            asm.load(refSlot, OBJECT_TYPE);
            asm.astore(OBJECT_TYPE);
            asm.invokespecial(
                    CALL_RESULT_INTERNAL_NAME,
                    "<init>",
                    Type.getMethodDescriptor(
                            VOID_TYPE, LONG_ARRAY_TYPE, Type.getType(Object[].class)),
                    false);
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
            asm.anew(Type.getType(CallResult.class));
            asm.dup();
            asm.load(longArraySlot, OBJECT_TYPE);
            asm.load(refArraySlot, OBJECT_TYPE);
            asm.invokespecial(
                    CALL_RESULT_INTERNAL_NAME,
                    "<init>",
                    Type.getMethodDescriptor(
                            VOID_TYPE, LONG_ARRAY_TYPE, Type.getType(Object[].class)),
                    false);
        } else if (returnType == long[].class) {
            // Multi-value, no Object refs: new CallResult(longArray, null)
            int longArraySlot = 4;
            asm.store(longArraySlot, OBJECT_TYPE);
            asm.anew(Type.getType(CallResult.class));
            asm.dup();
            asm.load(longArraySlot, OBJECT_TYPE);
            asm.aconst(null);
            asm.invokespecial(
                    CALL_RESULT_INTERNAL_NAME,
                    "<init>",
                    Type.getMethodDescriptor(
                            VOID_TYPE, LONG_ARRAY_TYPE, Type.getType(Object[].class)),
                    false);
        } else {
            // Single numeric return: new CallResult(new long[]{value}, null)
            emitJvmToLong(asm, type.returns().get(0));
            int longSlot = 4;
            asm.store(longSlot, LONG_TYPE);
            asm.anew(Type.getType(CallResult.class));
            asm.dup();
            asm.iconst(1);
            asm.newarray(LONG_TYPE);
            asm.dup();
            asm.iconst(0);
            asm.load(longSlot, LONG_TYPE);
            asm.astore(LONG_TYPE);
            asm.aconst(null);
            asm.invokespecial(
                    CALL_RESULT_INTERNAL_NAME,
                    "<init>",
                    Type.getMethodDescriptor(
                            VOID_TYPE, LONG_ARRAY_TYPE, Type.getType(Object[].class)),
                    false);
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
                        asm, classNameForFuncGroup(internalClassName, keys[i]), keys[i], type);
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
            asm.iconst(typeId);
            asm.load(funcId, INT_TYPE);
            asm.load(refInstance, OBJECT_TYPE);

            emitInvokeStatic(asm, CALL_INDIRECT_WITH_REFS);
            // returns CallResult
            emitUnboxCallResult(type, asm);
        } else {
            if (hasTooManyParameters(type)) {
                asm.load(0, LONG_ARRAY_TYPE);
            } else {
                emitBoxArguments(asm, type.params());
            }
            asm.iconst(typeId);
            asm.load(funcId, INT_TYPE);
            asm.load(refInstance, OBJECT_TYPE);

            emitInvokeStatic(asm, CALL_INDIRECT);

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
                    asm, classNameForFuncGroup(internalClassName, keys[i]), keys[i], type);
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

        var ctx =
                new Context(
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
                        analysis.maxTempSlots());

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
        localsCount += body.localTypes().size();
        for (int i = type.params().size(); i < localsCount; i++) {
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
    }
}
