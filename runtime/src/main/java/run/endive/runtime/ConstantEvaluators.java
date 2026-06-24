package run.endive.runtime;

import static run.endive.wasm.types.OpCode.GLOBAL_GET;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import run.endive.wasm.InvalidException;
import run.endive.wasm.MalformedException;
import run.endive.wasm.types.Instruction;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

@SuppressWarnings("deprecation")
public final class ConstantEvaluators {
    private ConstantEvaluators() {}

    public static final class ConstantResult {
        private final long[] longs;
        private final Object ref;

        ConstantResult(long[] longs, Object ref) {
            this.longs = longs;
            this.ref = ref;
        }

        public long[] longs() {
            return longs;
        }

        public Object ref() {
            return ref;
        }

        public long longValue() {
            return longs[0];
        }
    }

    public static long[] computeConstantValue(Instance instance, Instruction[] expr) {
        return computeConstant(instance, Arrays.asList(expr)).longs();
    }

    public static long[] computeConstantValue(Instance instance, List<Instruction> expr) {
        return computeConstant(instance, expr).longs();
    }

    public static ConstantResult computeConstant(Instance instance, List<Instruction> expr) {
        var stack = new ArrayDeque<ConstantResult>();
        for (var instruction : expr) {
            switch (instruction.opcode()) {
                case I32_ADD:
                    {
                        var x = (int) stack.pop().longValue();
                        var y = (int) stack.pop().longValue();
                        stack.push(longResult(x + y));
                        break;
                    }
                case I32_SUB:
                    {
                        var x = (int) stack.pop().longValue();
                        var y = (int) stack.pop().longValue();
                        stack.push(longResult(y - x));
                        break;
                    }
                case I32_MUL:
                    {
                        var x = (int) stack.pop().longValue();
                        var y = (int) stack.pop().longValue();
                        int res = x * y;
                        stack.push(longResult(res));
                        break;
                    }
                case I64_ADD:
                    {
                        var x = stack.pop().longValue();
                        var y = stack.pop().longValue();
                        stack.push(longResult(x + y));
                        break;
                    }
                case I64_SUB:
                    {
                        var x = stack.pop().longValue();
                        var y = stack.pop().longValue();
                        stack.push(longResult(y - x));
                        break;
                    }
                case I64_MUL:
                    {
                        var x = stack.pop().longValue();
                        var y = stack.pop().longValue();
                        stack.push(longResult(x * y));
                        break;
                    }
                case V128_CONST:
                    {
                        stack.push(
                                new ConstantResult(
                                        new long[] {instruction.operand(0), instruction.operand(1)},
                                        null));
                        break;
                    }
                case F32_CONST:
                case F64_CONST:
                case I32_CONST:
                case I64_CONST:
                case REF_FUNC:
                    {
                        stack.push(longResult(instruction.operand(0)));
                        break;
                    }
                case REF_NULL:
                    {
                        stack.push(new ConstantResult(new long[] {Value.REF_NULL_VALUE}, null));
                        break;
                    }
                case GLOBAL_GET:
                    {
                        var idx = (int) instruction.operand(0);
                        var global = instance.global(idx);
                        if (global == null) {
                            throw new InvalidException("unknown global");
                        }
                        if (global.getType().equals(ValType.V128)) {
                            stack.push(
                                    new ConstantResult(
                                            new long[] {
                                                global.getValueLow(), global.getValueHigh()
                                            },
                                            null));
                        } else if (global.getType().isReference()) {
                            stack.push(
                                    new ConstantResult(
                                            new long[] {global.getValueLow()},
                                            global.getRefValue()));
                        } else {
                            stack.push(longResult(global.getValueLow()));
                        }
                        break;
                    }
                case REF_I31:
                    {
                        var val = (int) stack.pop().longValue();
                        stack.push(new ConstantResult(new long[] {0}, new WasmI31Ref(val)));
                        break;
                    }
                case STRUCT_NEW:
                    {
                        var typeIdx = (int) instruction.operand(0);
                        var structType =
                                instance.module()
                                        .typeSection()
                                        .getSubType(typeIdx)
                                        .compType()
                                        .structType();
                        var fieldCount = structType.fieldTypes().length;
                        var fields = new long[fieldCount];
                        var fieldRefs = new Object[fieldCount];
                        for (int i = fieldCount - 1; i >= 0; i--) {
                            var entry = stack.pop();
                            var ft = structType.fieldTypes()[i];
                            if (ft.storageType().isObjectRef()) {
                                fieldRefs[i] = entry.ref();
                            } else {
                                fields[i] = entry.longValue();
                            }
                        }
                        var struct = new WasmStruct(typeIdx, fields, fieldRefs);
                        stack.push(new ConstantResult(new long[] {0}, struct));
                        break;
                    }
                case STRUCT_NEW_DEFAULT:
                    {
                        var typeIdx = (int) instruction.operand(0);
                        var structType =
                                instance.module()
                                        .typeSection()
                                        .getSubType(typeIdx)
                                        .compType()
                                        .structType();
                        var fieldCount = structType.fieldTypes().length;
                        var fields = new long[fieldCount];
                        var fieldRefs = new Object[fieldCount];
                        // Non-GC reference fields default to REF_NULL_VALUE
                        for (int i = 0; i < fieldCount; i++) {
                            var ft = structType.fieldTypes()[i];
                            if (ft.storageType().valType() != null
                                    && ft.storageType().valType().isReference()
                                    && !ft.storageType().isObjectRef()) {
                                fields[i] = Value.REF_NULL_VALUE;
                            }
                        }
                        var struct = new WasmStruct(typeIdx, fields, fieldRefs);
                        stack.push(new ConstantResult(new long[] {0}, struct));
                        break;
                    }
                case ARRAY_NEW:
                    {
                        var typeIdx = (int) instruction.operand(0);
                        var len = (int) stack.pop().longValue();
                        var fillEntry = stack.pop();
                        var at =
                                instance.module()
                                        .typeSection()
                                        .getSubType(typeIdx)
                                        .compType()
                                        .arrayType();
                        var elements = new long[len];
                        var elementRefs = new Object[len];
                        if (at.fieldType().storageType().isObjectRef()) {
                            Arrays.fill(elementRefs, fillEntry.ref());
                        } else {
                            Arrays.fill(elements, fillEntry.longValue());
                        }
                        var array = new WasmArray(typeIdx, elements, elementRefs);
                        stack.push(new ConstantResult(new long[] {0}, array));
                        break;
                    }
                case ARRAY_NEW_DEFAULT:
                    {
                        var typeIdx = (int) instruction.operand(0);
                        var len = (int) stack.pop().longValue();
                        var elements = new long[len];
                        var elementRefs = new Object[len];
                        var at =
                                instance.module()
                                        .typeSection()
                                        .getSubType(typeIdx)
                                        .compType()
                                        .arrayType();
                        var ft = at.fieldType();
                        if (ft.storageType().valType() != null
                                && ft.storageType().valType().isReference()
                                && !ft.storageType().isObjectRef()) {
                            Arrays.fill(elements, Value.REF_NULL_VALUE);
                        }
                        var array = new WasmArray(typeIdx, elements, elementRefs);
                        stack.push(new ConstantResult(new long[] {0}, array));
                        break;
                    }
                case ARRAY_NEW_FIXED:
                    {
                        var typeIdx = (int) instruction.operand(0);
                        var len = (int) instruction.operand(1);
                        var at =
                                instance.module()
                                        .typeSection()
                                        .getSubType(typeIdx)
                                        .compType()
                                        .arrayType();
                        var elements = new long[len];
                        var elementRefs = new Object[len];
                        boolean isGcRef = at.fieldType().storageType().isObjectRef();
                        for (int i = len - 1; i >= 0; i--) {
                            var entry = stack.pop();
                            if (isGcRef) {
                                elementRefs[i] = entry.ref();
                            } else {
                                elements[i] = entry.longValue();
                            }
                        }
                        var array = new WasmArray(typeIdx, elements, elementRefs);
                        stack.push(new ConstantResult(new long[] {0}, array));
                        break;
                    }
                case ANY_CONVERT_EXTERN:
                case EXTERN_CONVERT_ANY:
                    {
                        break;
                    }
                case END:
                    {
                        break;
                    }
                default:
                    throw new MalformedException(
                            "Invalid instruction in constant value" + instruction);
            }
        }

        return stack.pop();
    }

    private static ConstantResult longResult(long value) {
        return new ConstantResult(new long[] {value}, null);
    }

    public static Instance computeConstantInstance(Instance instance, List<Instruction> expr) {
        for (Instruction instruction : expr) {
            if (instruction.opcode() == GLOBAL_GET) {
                return instance.global((int) instruction.operand(0)).getInstance();
            }
        }
        return instance;
    }
}
