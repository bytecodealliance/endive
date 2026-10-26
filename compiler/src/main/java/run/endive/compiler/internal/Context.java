package run.endive.compiler.internal;

import static run.endive.compiler.internal.CompilerUtil.hasTooManyParameters;
import static run.endive.compiler.internal.CompilerUtil.slotCount;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionBody;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.TypeSection;
import run.endive.wasm.types.ValType;

/**
 * Class for tracking context relevant to compiling a single function
 */
final class Context {

    private final WasmModule module;
    private final String internalClassName;
    private final int maxFunctionsPerClass;
    private final List<ValType> globalTypes;
    private final List<FunctionType> functionTypes;
    private final int funcId;
    private final FunctionType type;
    private final FunctionBody body;
    private final boolean[] tailCallFunctions;
    private final boolean[] tailCallTypes;
    private final List<Integer> slots;
    private final int memorySlot;
    private final int instanceSlot;
    private final int tempSlot;
    private final int trySaveBaseSlot;
    private final IntFunction<String> callIndirectClassResolver;
    private final IntFunction<String> methodNames;

    public Context(
            WasmModule module,
            String internalClassName,
            int maxFunctionsPerClass,
            List<ValType> globalTypes,
            List<FunctionType> functionTypes,
            int funcId,
            FunctionType type,
            FunctionBody body,
            boolean[] tailCallFunctions,
            boolean[] tailCallTypes,
            IntFunction<String> callIndirectClassResolver,
            int maxTempSlots,
            IntFunction<String> methodNames) {
        this(
                module,
                internalClassName,
                maxFunctionsPerClass,
                globalTypes,
                functionTypes,
                funcId,
                type,
                body,
                tailCallFunctions,
                tailCallTypes,
                callIndirectClassResolver,
                maxTempSlots,
                methodNames,
                false);
    }

    // forceFrameLayout lays the JVM slots out as if the function had too many
    // parameters -- slot 0 holds a long[], Memory and Instance follow, and every
    // local (params first, then declared) lives in a slot after those. Trampoline
    // -split functions (see Compiler) use that layout for their chunk methods even
    // though their real signature is small, so their locals can round-trip through
    // the frame array between chunks. Nothing else about compilation changes: the
    // hasTooManyParameters checks in Emitters all concern the *callee's* type, not
    // the layout of the function currently being emitted.
    public Context(
            WasmModule module,
            String internalClassName,
            int maxFunctionsPerClass,
            List<ValType> globalTypes,
            List<FunctionType> functionTypes,
            int funcId,
            FunctionType type,
            FunctionBody body,
            boolean[] tailCallFunctions,
            boolean[] tailCallTypes,
            IntFunction<String> callIndirectClassResolver,
            int maxTempSlots,
            IntFunction<String> methodNames,
            boolean forceFrameLayout) {
        this.module = module;
        this.internalClassName = internalClassName;
        this.maxFunctionsPerClass = maxFunctionsPerClass;
        this.globalTypes = globalTypes;
        this.functionTypes = functionTypes;
        this.funcId = funcId;
        this.type = type;
        this.body = body;
        this.tailCallFunctions = tailCallFunctions;
        this.tailCallTypes = tailCallTypes;
        this.callIndirectClassResolver = callIndirectClassResolver;
        this.methodNames = methodNames;

        // compute JVM slot indices for WASM locals
        List<Integer> slots = new ArrayList<>(type.params().size() + body.localTypes().size());
        int slot = 0;
        boolean longArrayParams = hasTooManyParameters(type) || forceFrameLayout;

        // WASM arguments
        if (longArrayParams) {
            slot += 1; // long[]
        } else {
            for (ValType param : type.params()) {
                slots.add(slot);
                slot += slotCount(param);
            }
        }

        // extra arguments
        this.memorySlot = slot;
        slot++;
        this.instanceSlot = slot;
        slot++;

        // the long[] gets unboxed
        if (longArrayParams) {
            for (ValType param : type.params()) {
                slots.add(slot);
                slot += slotCount(param);
            }
        }

        // WASM locals
        for (ValType local : body.localTypes()) {
            slots.add(slot);
            slot += slotCount(local);
        }

        this.slots = List.copyOf(slots);
        this.tempSlot = slot;
        this.trySaveBaseSlot = slot + maxTempSlots;
    }

    public String internalClassName() {
        return internalClassName;
    }

    public List<ValType> globalTypes() {
        return globalTypes;
    }

    public List<FunctionType> functionTypes() {
        return functionTypes;
    }

    public FunctionType type(int idx) {
        return module.typeSection().getType(idx);
    }

    public FunctionType[] types() {
        return module.typeSection().types();
    }

    public TypeSection typeSection() {
        return module.typeSection();
    }

    public String methodNameForFunc(int funcId) {
        return methodNames.apply(funcId);
    }

    public int getId() {
        return funcId;
    }

    public FunctionType getType() {
        return type;
    }

    public FunctionBody getBody() {
        return body;
    }

    public boolean needsTailCallCheck(int funcId) {
        return funcId >= 0 && funcId < tailCallFunctions.length && tailCallFunctions[funcId];
    }

    public boolean needsTailCallCheckForType(int typeId) {
        return typeId >= 0 && typeId < tailCallTypes.length && tailCallTypes[typeId];
    }

    public int localSlotIndex(int localIndex) {
        return slots.get(localIndex);
    }

    public int memorySlot() {
        return memorySlot;
    }

    public int instanceSlot() {
        return instanceSlot;
    }

    public int tempSlot() {
        return tempSlot;
    }

    public int trySaveBaseSlot() {
        return trySaveBaseSlot;
    }

    public String callIndirectClassName(int typeId) {
        return callIndirectClassResolver.apply(typeId);
    }

    public String classNameForFuncGroup(String prefix, int funcId) {
        return prefix + "FuncGroup_" + (funcId / maxFunctionsPerClass);
    }

    public FunctionType tagFunctionType(int tagId) {
        var type = CompilerUtil.resolveTagType(tagId, module);
        if (type == null) {
            throw new IllegalStateException("Tag not found: " + tagId);
        }
        return type;
    }
}
