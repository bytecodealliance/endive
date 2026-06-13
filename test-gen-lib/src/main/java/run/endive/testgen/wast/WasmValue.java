package run.endive.testgen.wast;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.javaparser.ast.expr.NameExpr;

public class WasmValue {

    @JsonProperty("type")
    private WasmValueType type;

    @JsonProperty("value")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private String[] value;

    @JsonProperty("lane_type")
    private LaneType laneType;

    public WasmValueType type() {
        return type;
    }

    public LaneType laneType() {
        return laneType;
    }

    public String toResultValue(String result) {
        switch (type) {
            case I64:
                return result;
            case I32:
                return "(int) " + result;
            case F32:
                return "Float.intBitsToFloat((int) " + result + "), 0.0";
            case F64:
                return "Double.longBitsToDouble(" + result + "), 0.0";
            case EXTERN_REF:
            case EXN_REF:
            case FUNC_REF:
            case STRUCT_REF:
            case ANY_REF:
            case NULL_REF:
            case NULL_FUNC_REF:
            case NULL_EXTERN_REF:
            case ARRAY_REF:
            case EQ_REF:
            case I31_REF:
            case REF_NULL:
                if (result.equals("null")) {
                    return "Value.REF_NULL_VALUE";
                }
                return result;
            case V128:
                {
                    var sb = new StringBuilder();
                    switch (laneType) {
                        case I8:
                            sb.append("new byte[] {");
                            break;
                        case I16:
                            sb.append("new int[] {");
                            break;
                        case I32:
                        case I64:
                            sb.append("new long[] {");
                            break;
                        case F32:
                            sb.append("new float[] {");
                            break;
                        case F64:
                            sb.append("new double[] {");
                            break;
                    }
                    var first = true;
                    for (var v : value) {
                        if (first) {
                            first = false;
                        } else {
                            sb.append(", ");
                        }

                        switch (laneType) {
                            case I8:
                                sb.append("(byte) (0xFF & Integer.parseInt(\"" + v + "\"))");
                                break;
                            case I16:
                                sb.append(shortLaneValue(v));
                                break;
                            case I32:
                                sb.append(intLaneValue(v));
                                break;
                            case I64:
                                sb.append("Long.parseLong(\"" + v + "\")");
                                break;
                            case F32:
                                switch (v) {
                                    case "nan:canonical":
                                    case "nan:arithmetic":
                                        sb.append("Float.NaN");
                                        break;
                                    default:
                                        sb.append(
                                                "Float.intBitsToFloat(Integer.parseUnsignedInt(\""
                                                        + v
                                                        + "\"))");
                                        break;
                                }
                                break;
                            case F64:
                                switch (v) {
                                    case "nan:canonical":
                                    case "nan:arithmetic":
                                        sb.append("Double.NaN");
                                        break;
                                    default:
                                        sb.append(
                                                "Double.longBitsToDouble(Long.parseUnsignedLong(\""
                                                        + v
                                                        + "\"))");
                                        break;
                                }
                                break;
                        }
                    }
                    sb.append(" }");
                    return sb.toString();
                }
            default:
                throw new IllegalArgumentException("Type not recognized " + type);
        }
    }

    public NameExpr toAssertion(String resultVar, String moduleName) {
        if (value == null) {
            // according to
            // https://github.com/WebAssembly/spec/blob/05949f507908aac3ad2a21661b5c39fa013da950/interpreter/script/js.ml#L150
            // ref.func should check that its a function, and ref.extern should check the returned
            // reference is not null
            switch (type) {
                case FUNC_REF:
                    return new NameExpr("assert " + resultVar + " >= 0");
                case EXTERN_REF:
                    return new NameExpr(
                            "assertNotEquals(" + resultVar + ", " + "REF_NULL_VALUE" + ")");
                case REF_NULL:
                    return new NameExpr(
                            "assertEquals(" + resultVar + ", " + "REF_NULL_VALUE" + ")");
                case ARRAY_REF:
                    return new NameExpr(
                            "assertNotNull(" + moduleName + ".array((int) results[0]))");
                case EQ_REF:
                    // just verifying the reference exists
                    return new NameExpr(
                            "assertNotNull(" + moduleName + ".array((int) results[0]))");
                case NULL_REF:
                case NULL_FUNC_REF:
                case NULL_EXTERN_REF:
                    return new NameExpr(
                            "assertEquals(" + resultVar + ", " + "REF_NULL_VALUE" + ")");
                case STRUCT_REF:
                case ANY_REF:
                case I31_REF:
                    return new NameExpr(
                            "assertNotEquals(" + resultVar + ", " + "REF_NULL_VALUE" + ")");
                default:
                    throw new IllegalArgumentException(
                            "cannot generate assertion for WasmValue: " + this);
            }
        }

        var expectedVar = toExpectedValue();
        return new NameExpr("assertEquals(" + expectedVar + ", " + resultVar + ")");
    }

    public String toExpectedValue() {
        switch (type) {
            case I32:
                return "Integer.parseInt(\"" + value[0] + "\")";
            case I64:
                return "Long.parseLong(\"" + value[0] + "\")";
            case F32:
                if (value[0] != null) {
                    switch (value[0]) {
                        case "nan:canonical":
                        case "nan:arithmetic":
                            return "Float.NaN";
                        default:
                            return "Float.intBitsToFloat(Integer.parseUnsignedInt(\""
                                    + value[0]
                                    + "\"))";
                    }
                } else {
                    return "null";
                }
            case F64:
                if (value[0] != null) {
                    switch (value[0]) {
                        case "nan:canonical":
                        case "nan:arithmetic":
                            return "Double.NaN";
                        default:
                            return "Double.longBitsToDouble(Long.parseUnsignedLong(\""
                                    + value[0]
                                    + "\"))";
                    }
                } else {
                    return "null";
                }
            case EXTERN_REF:
            case EXN_REF:
            case STRUCT_REF:
            case ANY_REF:
            case NULL_REF:
            case NULL_FUNC_REF:
            case NULL_EXTERN_REF:
            case ARRAY_REF:
            case EQ_REF:
            case FUNC_REF:
                if (value[0].equals("null")) {
                    return "Value.REF_NULL_VALUE";
                }
                return value[0];
            case V128:
                {
                    var sb = new StringBuilder();
                    switch (laneType) {
                        case I8:
                            sb.append("new byte[] {");
                            break;
                        case I16:
                            sb.append("new int[] {");
                            break;
                        case I32:
                        case I64:
                            sb.append("new long[] {");
                            break;
                        case F32:
                            sb.append("new float[] {");
                            break;
                        case F64:
                            sb.append("new double[] {");
                            break;
                    }
                    var first = true;
                    for (var v : value) {
                        if (first) {
                            first = false;
                        } else {
                            sb.append(", ");
                        }

                        switch (laneType) {
                            case I8:
                                sb.append("(byte) (0xFF & Integer.parseInt(\"" + v + "\"))");
                                break;
                            case I16:
                                sb.append(shortLaneValue(v));
                                break;
                            case I32:
                                sb.append(intLaneValue(v));
                                break;
                            case I64:
                                sb.append("Long.parseLong(\"" + v + "\")");
                                break;
                            case F32:
                                sb.append("Integer.parseUnsignedInt(\"" + v + "\")");
                                break;
                            case F64:
                                sb.append("Long.parseUnsignedLong(\"" + v + "\")");
                                break;
                        }
                    }
                    sb.append(" }");
                    return sb.toString();
                }
            default:
                throw new IllegalArgumentException("Type not recognized " + type);
        }
    }

    public String shortLaneValue(String v) {
        var intValue = Integer.parseInt(v);
        return Integer.toString(0xFFFF & intValue);
    }

    public String intLaneValue(String v) {
        var longValue = Long.parseLong(v);
        return Integer.toUnsignedString((int) (0xFFFFFFFF & longValue)) + "L";
    }

    /**
     * Generate an arg value for use with applyGc(Object...).
     * Numeric types are boxed, GC ref types use null or the value directly.
     */
    public String toGcArgsValue() {
        switch (type) {
            case I32:
                return "(Object) (long) (int) Integer.parseInt(\"" + value[0] + "\")";
            case F32:
                if (value[0] != null) {
                    switch (value[0]) {
                        case "nan:canonical":
                        case "nan:arithmetic":
                            return "(Object) (long) (int) (int) Float.NaN";
                        default:
                            return "(Object) (long) (int) Integer.parseUnsignedInt(\""
                                    + value[0]
                                    + "\")";
                    }
                } else {
                    return "null";
                }
            case I64:
                return "(Object) (long) Long.parseLong(\"" + value[0] + "\")";
            case F64:
                if (value[0] != null) {
                    switch (value[0]) {
                        case "nan:canonical":
                        case "nan:arithmetic":
                            return "(Object) (long) (long) Double.NaN";
                        default:
                            return "(Object) (long) Long.parseUnsignedLong(\"" + value[0] + "\")";
                    }
                } else {
                    return "null";
                }
            case EXTERN_REF:
            case EXN_REF:
            case FUNC_REF:
            case NULL_FUNC_REF:
            case NULL_EXTERN_REF:
                if (value[0].equals("null")) {
                    return "(Object) Value.REF_NULL_VALUE";
                }
                return "(Object) (long) Long.parseLong(\"" + value[0] + "\")";
            case STRUCT_REF:
            case ANY_REF:
            case NULL_REF:
            case ARRAY_REF:
            case EQ_REF:
            case I31_REF:
            case REF_NULL:
                if (value[0].equals("null")) {
                    return "(Object) null";
                }
                return "(Object) (long) Long.parseLong(\"" + value[0] + "\")";
            default:
                throw new IllegalArgumentException("Type not recognized for GC args: " + type);
        }
    }

    /**
     * Generate the result extraction expression for Object[] results from applyGc.
     * callGc uses boxReturnValue which returns:
     *   I32 -> Integer, I64 -> Long, F32 -> Float, F64 -> Double
     * GC ref types are returned as Objects directly via popRef().
     */
    public String toGcResultValue(String result) {
        switch (type) {
            case I64:
                return "(long)(Long) " + result;
            case I32:
                return "(int)(Integer) " + result;
            case F32:
                return "(float)(Float) " + result + ", 0.0";
            case F64:
                return "(double)(Double) " + result + ", 0.0";
            case EXTERN_REF:
            case EXN_REF:
            case FUNC_REF:
            case NULL_FUNC_REF:
            case NULL_EXTERN_REF:
            case STRUCT_REF:
            case ANY_REF:
            case NULL_REF:
            case ARRAY_REF:
            case EQ_REF:
            case I31_REF:
            case REF_NULL:
                // All ref types are returned as Objects
                return result;
            case V128:
                return toResultValue(result);
            default:
                throw new IllegalArgumentException("Type not recognized " + type);
        }
    }

    /**
     * Generate assertion for Object[] results from applyGc.
     *
     * GC ref types (anyref, structref, etc.) are returned via popRef() as Java Objects,
     * so null refs are Java null. Non-GC ref types (externref, funcref) are returned
     * via boxReturnValue() as Long values, so null refs are REF_NULL_VALUE (-1).
     */
    public NameExpr toGcAssertion(String resultVar, String moduleName) {
        if (value == null) {
            // Type-only assertion (no specific value)
            switch (type) {
                case FUNC_REF:
                    return new NameExpr("assertNotNull(" + resultVar + ")");
                case EXTERN_REF:
                    return new NameExpr("assertNotNull(" + resultVar + ")");
                case REF_NULL:
                case NULL_REF:
                    // These are GC null types -> Java null from popRef()
                    return new NameExpr("assertNull(" + resultVar + ")");
                case NULL_FUNC_REF:
                case NULL_EXTERN_REF:
                    return new NameExpr("assertNull(" + resultVar + ")");
                case STRUCT_REF:
                case ANY_REF:
                case I31_REF:
                case ARRAY_REF:
                case EQ_REF:
                    return new NameExpr("assertNotNull(" + resultVar + ")");
                default:
                    throw new IllegalArgumentException(
                            "cannot generate GC assertion for WasmValue: " + this);
            }
        }

        // Value-based assertion
        switch (type) {
            case STRUCT_REF:
            case ANY_REF:
            case NULL_REF:
            case ARRAY_REF:
            case EQ_REF:
            case I31_REF:
            case REF_NULL:
                // GC ref types: null -> Java null, non-null -> assertNotNull
                if (value[0].equals("null")) {
                    return new NameExpr("assertNull(" + resultVar + ")");
                }
                return new NameExpr("assertNotNull(" + resultVar + ")");
            case EXTERN_REF:
            case EXN_REF:
            case FUNC_REF:
            case NULL_FUNC_REF:
            case NULL_EXTERN_REF:
                if (value[0].equals("null")) {
                    return new NameExpr("assertNull(" + resultVar + ")");
                }
                return new NameExpr("assertNotNull(" + resultVar + ")");
            default:
                // Numeric types in GC path
                var expectedVar = toExpectedValue();
                return new NameExpr("assertEquals(" + expectedVar + ", " + resultVar + ")");
        }
    }

    public String toArgsValue() {
        switch (type) {
            case I32:
                return "Integer.parseInt(\"" + value[0] + "\")";
            case F32:
                if (value[0] != null) {
                    switch (value[0]) {
                        case "nan:canonical":
                        case "nan:arithmetic":
                            return "(int) Float.NaN";
                        default:
                            return "Integer.parseUnsignedInt(\"" + value[0] + "\")";
                    }
                } else {
                    return "null";
                }
            case I64:
                return "Long.parseLong(\"" + value[0] + "\")";
            case F64:
                if (value[0] != null) {
                    switch (value[0]) {
                        case "nan:canonical":
                        case "nan:arithmetic":
                            return "(long) Double.NaN";
                        default:
                            return "Long.parseUnsignedLong(\"" + value[0] + "\")";
                    }
                } else {
                    return "null";
                }
            case EXTERN_REF:
            case EXN_REF:
            case STRUCT_REF:
            case ANY_REF:
            case NULL_REF:
            case NULL_FUNC_REF:
            case NULL_EXTERN_REF:
            case ARRAY_REF:
            case EQ_REF:
            case I31_REF:
            case FUNC_REF:
                if (value[0].toString().equals("null")) {
                    return "Value.REF_NULL_VALUE";
                }
                return value[0];
            case V128:
                var sb = new StringBuilder();

                switch (laneType) {
                    case I8:
                        sb.append("i8ToVec( ");
                        break;
                    case I16:
                        sb.append("i16ToVec( ");
                        break;
                    case I32:
                        sb.append("i32ToVec( ");
                        break;
                    case I64:
                        sb.append("i64ToVec( ");
                        break;
                    case F32:
                        sb.append("f32ToVec( ");
                        break;
                    case F64:
                        sb.append("f64ToVec( ");
                }

                sb.append("new long[] { ");
                var first = true;
                for (var v : value) {
                    if (first) {
                        first = false;
                    } else {
                        sb.append(", ");
                    }

                    switch (laneType) {
                        case I8:
                            sb.append("(byte) (0xFF & Integer.parseInt(\"" + v + "\"))");
                            break;
                        case I16:
                            sb.append(shortLaneValue(v));
                            break;
                        case I32:
                            sb.append(intLaneValue(v));
                            break;
                        case I64:
                            sb.append("Long.parseLong(\"" + v + "\")");
                            break;
                        case F32:
                            sb.append("Integer.parseUnsignedInt(\"" + v + "\")");
                            break;
                        case F64:
                            sb.append("Long.parseUnsignedLong(\"" + v + "\")");
                            break;
                    }
                }
                sb.append(" })");
                return sb.toString();
            default:
                throw new IllegalArgumentException("Type not recognized " + type);
        }
    }
}
