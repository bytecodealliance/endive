package run.endive.compiler.internal;

import static run.endive.compiler.internal.CompilerUtil.hasTooManyParameters;
import static run.endive.compiler.internal.CompilerUtil.paramSlots;
import static run.endive.compiler.internal.CompilerUtil.slotCount;

import java.util.List;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;

final class TempSlotCalculators {

    private TempSlotCalculators() {}

    static int dropKeep(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        int keepStart = (int) ins.operand(0) + 1;
        int slots = 0;
        for (int i = keepStart; i < ins.operandCount(); i++) {
            slots += slotCount(ins.operand(i));
        }
        return slots;
    }

    static int call(CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        var type = functionTypes.get((int) ins.operand(0));
        if (hasTooManyParameters(type)) {
            return paramSlots(type);
        }
        return 0;
    }

    static int callIndirect(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        var type = module.typeSection().getType((int) ins.operand(0));
        if (hasTooManyParameters(type)) {
            // the boxed arguments and the funcTableIdx
            return paramSlots(type) + 1;
        }
        return 0;
    }

    static int returnCall(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        return paramSlots(functionTypes.get((int) ins.operand(0)));
    }

    static int returnCallIndirect(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        var type = module.typeSection().getType((int) ins.operand(0));
        return paramSlots(type) + 1;
    }

    static int returnCallRef(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        var type = module.typeSection().getType((int) ins.operand(0));
        return paramSlots(type) + 1;
    }

    static int throwOp(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        var tagType = CompilerUtil.resolveTagType((int) ins.operand(0), module);
        return tagType != null ? paramSlots(tagType) : 0;
    }

    static int catchStart(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        return 3;
    }

    static int trySaveStack(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        int belowCount = (int) ins.operand(1);
        int totalCount = ins.operandCount() - 2;
        int slots = 0;
        for (int i = belowCount; i < totalCount; i++) {
            slots += slotCount(ins.operand(i + 2));
        }
        return slots;
    }

    static int callRef(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        var type = module.typeSection().getType((int) ins.operand(0));
        // the funcref, above the boxed arguments
        if (hasTooManyParameters(type)) {
            return paramSlots(type) + 1;
        }
        return 1;
    }

    static int structNew(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        var st = module.typeSection().getSubType((int) ins.operand(0)).compType().structType();
        int slots = 0;
        for (var ft : st.fieldTypes()) {
            if (ft.storageType().valType() != null) {
                slots += slotCount(ft.storageType().valType().id());
            } else {
                slots += 1;
            }
        }
        return slots;
    }

    static int arrayNewFixed(
            CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        int len = (int) ins.operand(1);
        var at = module.typeSection().getSubType((int) ins.operand(0)).compType().arrayType();
        if (at.fieldType().storageType().isObjectRef()) {
            return len;
        }
        int elemSlots;
        if (at.fieldType().storageType().valType() != null) {
            elemSlots = slotCount(at.fieldType().storageType().valType().id());
        } else {
            elemSlots = 1;
        }
        return len * elemSlots;
    }

    static int one(CompilerInstruction ins, WasmModule module, List<FunctionType> functionTypes) {
        return 1;
    }
}
