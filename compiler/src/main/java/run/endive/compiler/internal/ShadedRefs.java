package run.endive.compiler.internal;

import java.lang.reflect.Method;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TableInstance;
import run.endive.runtime.WasmException;
import run.endive.runtime.internal.CompilerInterpreterMachine;
import run.endive.wasm.types.Element;

public final class ShadedRefs {

    static final Method CHECK_INTERRUPTION;
    static final Method CALL_INDIRECT;
    static final Method CALL_INDIRECT_ON_INTERPRETER;
    static final Method CALL_INDIRECT_ON_INTERPRETER_WITH_REFS;
    static final Method INSTANCE_MEMORY;
    static final Method INSTANCE_MEMORY_IDX;
    static final Method CALL_HOST_FUNCTION;
    static final Method READ_GLOBAL;
    static final Method READ_GLOBAL_REF;
    static final Method WRITE_GLOBAL;
    static final Method WRITE_GLOBAL_REF;
    static final Method INSTANCE_SET_ELEMENT;
    static final Method INSTANCE_TABLE;
    static final Method MEMORY_COPY;
    static final Method MEMORY_COPY_2;
    static final Method MEMORY_FILL;
    static final Method MEMORY_INIT;
    static final Method MEMORY_GROW;
    static final Method MEMORY_DROP;
    static final Method MEMORY_PAGES;
    static final Method MEMORY_READ_BYTE;
    static final Method MEMORY_READ_SHORT;
    static final Method MEMORY_READ_INT;
    static final Method MEMORY_READ_LONG;
    static final Method MEMORY_READ_FLOAT;
    static final Method MEMORY_READ_DOUBLE;
    static final Method MEMORY_WRITE_BYTE;
    static final Method MEMORY_WRITE_SHORT;
    static final Method MEMORY_WRITE_INT;
    static final Method MEMORY_WRITE_LONG;
    static final Method MEMORY_WRITE_FLOAT;
    static final Method MEMORY_WRITE_DOUBLE;
    static final Method MEMORY_ATOMIC_INT_BYTE_READ;
    static final Method MEMORY_ATOMIC_INT_SHORT_READ;
    static final Method MEMORY_ATOMIC_INT_READ;
    static final Method MEMORY_ATOMIC_LONG_BYTE_READ;
    static final Method MEMORY_ATOMIC_LONG_SHORT_READ;
    static final Method MEMORY_ATOMIC_LONG_INT_READ;
    static final Method MEMORY_ATOMIC_LONG_READ;
    static final Method I32_GE_U;
    static final Method REF_IS_NULL;
    static final Method GC_REF_IS_NULL;
    static final Method REF_AS_NON_NULL;
    static final Method GC_REF_AS_NON_NULL;
    static final Method TABLE_GET;
    static final Method TABLE_GET_REF;
    static final Method TABLE_SET;
    static final Method TABLE_SET_REF;
    static final Method TABLE_SIZE;
    static final Method TABLE_GROW;
    static final Method TABLE_GROW_REF;
    static final Method TABLE_FILL;
    static final Method TABLE_FILL_REF;
    static final Method TABLE_COPY;
    static final Method TABLE_INIT;
    static final Method TABLE_REQUIRED_REF;
    static final Method TABLE_INSTANCE;
    static final Method THROW_CALL_STACK_EXHAUSTED;
    static final Method THROW_INDIRECT_CALL_TYPE_MISMATCH;
    static final Method THROW_OUT_OF_BOUNDS_MEMORY_ACCESS;
    static final Method THROW_TRAP_EXCEPTION;
    static final Method THROW_NULL_FUNCTION_REFERENCE;
    static final Method THROW_UNKNOWN_FUNCTION;
    static final Method AOT_INTERPRETER_MACHINE_CALL;

    // Exception handling methods
    static final Method CREATE_WASM_EXCEPTION;
    static final Method CREATE_WASM_EXCEPTION_GC;
    static final Method INSTANCE_GET_EXCEPTION;
    static final Method EXCEPTION_MATCHES;

    static final Method MEMORY_ATOMIC_INT_WRITE;
    static final Method MEMORY_ATOMIC_INT_BYTE_WRITE;
    static final Method MEMORY_ATOMIC_INT_SHORT_WRITE;
    static final Method MEMORY_ATOMIC_LONG_WRITE;
    static final Method MEMORY_ATOMIC_LONG_BYTE_WRITE;
    static final Method MEMORY_ATOMIC_LONG_SHORT_WRITE;
    static final Method MEMORY_ATOMIC_LONG_INT_WRITE;
    static final Method MEMORY_ATOMIC_INT_RMW_ADD;
    static final Method MEMORY_ATOMIC_INT_RMW_SUB;
    static final Method MEMORY_ATOMIC_INT_RMW_AND;
    static final Method MEMORY_ATOMIC_INT_RMW_OR;
    static final Method MEMORY_ATOMIC_INT_RMW_XOR;
    static final Method MEMORY_ATOMIC_INT_RMW_XCHG;
    static final Method MEMORY_ATOMIC_INT_RMW_CMPXCHG;

    static final Method MEMORY_ATOMIC_INT_RMW8_ADD_U;
    static final Method MEMORY_ATOMIC_INT_RMW8_SUB_U;
    static final Method MEMORY_ATOMIC_INT_RMW8_AND_U;
    static final Method MEMORY_ATOMIC_INT_RMW8_OR_U;
    static final Method MEMORY_ATOMIC_INT_RMW8_XOR_U;
    static final Method MEMORY_ATOMIC_INT_RMW8_XCHG_U;
    static final Method MEMORY_ATOMIC_INT_RMW8_CMPXCHG_U;
    static final Method MEMORY_ATOMIC_INT_RMW16_ADD_U;
    static final Method MEMORY_ATOMIC_INT_RMW16_SUB_U;
    static final Method MEMORY_ATOMIC_INT_RMW16_AND_U;
    static final Method MEMORY_ATOMIC_INT_RMW16_OR_U;
    static final Method MEMORY_ATOMIC_INT_RMW16_XOR_U;
    static final Method MEMORY_ATOMIC_INT_RMW16_XCHG_U;
    static final Method MEMORY_ATOMIC_INT_RMW16_CMPXCHG_U;
    static final Method MEMORY_ATOMIC_LONG_RMW_ADD;
    static final Method MEMORY_ATOMIC_LONG_RMW_SUB;
    static final Method MEMORY_ATOMIC_LONG_RMW_AND;
    static final Method MEMORY_ATOMIC_LONG_RMW_OR;
    static final Method MEMORY_ATOMIC_LONG_RMW_XOR;
    static final Method MEMORY_ATOMIC_LONG_RMW_XCHG;
    static final Method MEMORY_ATOMIC_LONG_RMW_CMPXCHG;
    static final Method MEMORY_ATOMIC_LONG_RMW8_ADD_U;
    static final Method MEMORY_ATOMIC_LONG_RMW8_SUB_U;
    static final Method MEMORY_ATOMIC_LONG_RMW8_AND_U;
    static final Method MEMORY_ATOMIC_LONG_RMW8_OR_U;
    static final Method MEMORY_ATOMIC_LONG_RMW8_XOR_U;
    static final Method MEMORY_ATOMIC_LONG_RMW8_XCHG_U;
    static final Method MEMORY_ATOMIC_LONG_RMW8_CMPXCHG_U;
    static final Method MEMORY_ATOMIC_LONG_RMW16_ADD_U;
    static final Method MEMORY_ATOMIC_LONG_RMW16_SUB_U;
    static final Method MEMORY_ATOMIC_LONG_RMW16_AND_U;
    static final Method MEMORY_ATOMIC_LONG_RMW16_OR_U;
    static final Method MEMORY_ATOMIC_LONG_RMW16_XOR_U;
    static final Method MEMORY_ATOMIC_LONG_RMW16_XCHG_U;
    static final Method MEMORY_ATOMIC_LONG_RMW16_CMPXCHG_U;
    static final Method MEMORY_ATOMIC_LONG_RMW32_ADD_U;
    static final Method MEMORY_ATOMIC_LONG_RMW32_SUB_U;
    static final Method MEMORY_ATOMIC_LONG_RMW32_AND_U;
    static final Method MEMORY_ATOMIC_LONG_RMW32_OR_U;
    static final Method MEMORY_ATOMIC_LONG_RMW32_XOR_U;
    static final Method MEMORY_ATOMIC_LONG_RMW32_XCHG_U;
    static final Method MEMORY_ATOMIC_LONG_RMW32_CMPXCHG_U;
    static final Method MEMORY_ATOMIC_WAIT32;
    static final Method MEMORY_ATOMIC_WAIT64;
    static final Method MEMORY_ATOMIC_NOTIFY;
    static final Method MEMORY_ATOMIC_FENCE;

    // Tail calls
    static final Method SET_TAIL_CALL;
    static final Method SET_TAIL_CALL_WITH_REFS;
    static final Method SET_TAIL_CALL_INDIRECT;
    static final Method SET_TAIL_CALL_INDIRECT_WITH_REFS;
    static final Method IS_TAIL_CALL_PENDING;
    static final Method RESOLVE_TAIL_CALL;
    static final Method RESOLVE_TAIL_CALL_WITH_REFS;

    // WithRefs overloads for cross-module/host calls
    static final Method CALL_HOST_FUNCTION_WITH_REFS;
    static final Method CALL_INDIRECT_WITH_REFS;

    // GC
    static final Method STRUCT_NEW;
    static final Method STRUCT_NEW_DEFAULT;
    static final Method STRUCT_GET;
    static final Method STRUCT_GET_REF;
    static final Method STRUCT_GET_S;
    static final Method STRUCT_GET_U;
    static final Method STRUCT_SET;
    static final Method STRUCT_SET_REF;
    static final Method ARRAY_NEW;
    static final Method ARRAY_NEW_REF;
    static final Method ARRAY_NEW_DEFAULT;
    static final Method ARRAY_NEW_FIXED;
    static final Method ARRAY_NEW_FIXED_REFS;
    static final Method ARRAY_NEW_DATA;
    static final Method ARRAY_NEW_ELEM;
    static final Method ARRAY_GET;
    static final Method ARRAY_GET_REF;
    static final Method ARRAY_GET_S;
    static final Method ARRAY_GET_U;
    static final Method ARRAY_SET;
    static final Method ARRAY_SET_REF;
    static final Method ARRAY_LEN;
    static final Method ARRAY_FILL;
    static final Method ARRAY_FILL_REF;
    static final Method ARRAY_COPY;
    static final Method ARRAY_INIT_DATA;
    static final Method ARRAY_INIT_ELEM;
    static final Method REF_TEST;
    static final Method REF_TEST_NULL;
    static final Method CAST_TEST;
    static final Method CAST_TEST_NULL;
    static final Method HEAP_TYPE_MATCH;
    // int-based variants for funcref/externref (non-GC refs, int on JVM stack)
    static final Method REF_TEST_INT;
    static final Method REF_TEST_NULL_INT;
    static final Method CAST_TEST_INT;
    static final Method CAST_TEST_NULL_INT;
    static final Method HEAP_TYPE_MATCH_INT;
    static final Method REF_EQ;
    static final Method REF_I31;
    static final Method I31_GET_S;
    static final Method I31_GET_U;
    static final Method ANY_CONVERT_EXTERN;
    static final Method EXTERN_CONVERT_ANY;
    static final Method DATA_DROP;

    static {
        try {
            CHECK_INTERRUPTION = Shaded.class.getMethod("checkInterruption");
            CALL_INDIRECT =
                    Shaded.class.getMethod(
                            "callIndirect", long[].class, int.class, int.class, Instance.class);
            CALL_INDIRECT_ON_INTERPRETER =
                    Shaded.class.getMethod("callIndirect", long[].class, int.class, Instance.class);
            CALL_INDIRECT_ON_INTERPRETER_WITH_REFS =
                    Shaded.class.getMethod(
                            "callIndirectOnInterpreterWithRefs",
                            long[].class,
                            Object[].class,
                            int.class,
                            Instance.class);
            INSTANCE_MEMORY = Instance.class.getMethod("memory");
            INSTANCE_MEMORY_IDX = Instance.class.getMethod("memory", int.class);
            CALL_HOST_FUNCTION =
                    Shaded.class.getMethod(
                            "callHostFunction", Instance.class, int.class, long[].class);
            READ_GLOBAL = Shaded.class.getMethod("readGlobal", int.class, Instance.class);
            READ_GLOBAL_REF = Shaded.class.getMethod("readGlobalRef", int.class, Instance.class);
            WRITE_GLOBAL =
                    Shaded.class.getMethod("writeGlobal", long.class, int.class, Instance.class);
            WRITE_GLOBAL_REF =
                    Shaded.class.getMethod(
                            "writeGlobalRef", Object.class, int.class, Instance.class);
            INSTANCE_SET_ELEMENT = Instance.class.getMethod("setElement", int.class, Element.class);
            INSTANCE_TABLE = Instance.class.getMethod("table", int.class);
            MEMORY_COPY =
                    Shaded.class.getMethod(
                            "memoryCopy", int.class, int.class, int.class, Memory.class);
            MEMORY_COPY_2 =
                    Shaded.class.getMethod(
                            "memoryCopy",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class,
                            Memory.class);
            MEMORY_FILL =
                    Shaded.class.getMethod(
                            "memoryFill", int.class, byte.class, int.class, Memory.class);
            MEMORY_INIT =
                    Shaded.class.getMethod(
                            "memoryInit", int.class, int.class, int.class, int.class, Memory.class);
            MEMORY_GROW = Shaded.class.getMethod("memoryGrow", int.class, Memory.class);
            MEMORY_DROP = Shaded.class.getMethod("memoryDrop", int.class, Memory.class);
            MEMORY_PAGES = Shaded.class.getMethod("memoryPages", Memory.class);
            MEMORY_READ_BYTE =
                    Shaded.class.getMethod("memoryReadByte", int.class, int.class, Memory.class);
            MEMORY_READ_SHORT =
                    Shaded.class.getMethod("memoryReadShort", int.class, int.class, Memory.class);
            MEMORY_READ_INT =
                    Shaded.class.getMethod("memoryReadInt", int.class, int.class, Memory.class);
            MEMORY_READ_LONG =
                    Shaded.class.getMethod("memoryReadLong", int.class, int.class, Memory.class);
            MEMORY_READ_FLOAT =
                    Shaded.class.getMethod("memoryReadFloat", int.class, int.class, Memory.class);
            MEMORY_READ_DOUBLE =
                    Shaded.class.getMethod("memoryReadDouble", int.class, int.class, Memory.class);
            MEMORY_WRITE_BYTE =
                    Shaded.class.getMethod(
                            "memoryWriteByte", int.class, byte.class, int.class, Memory.class);
            MEMORY_WRITE_SHORT =
                    Shaded.class.getMethod(
                            "memoryWriteShort", int.class, short.class, int.class, Memory.class);
            MEMORY_WRITE_INT =
                    Shaded.class.getMethod(
                            "memoryWriteInt", int.class, int.class, int.class, Memory.class);
            MEMORY_WRITE_LONG =
                    Shaded.class.getMethod(
                            "memoryWriteLong", int.class, long.class, int.class, Memory.class);
            MEMORY_WRITE_FLOAT =
                    Shaded.class.getMethod(
                            "memoryWriteFloat", int.class, float.class, int.class, Memory.class);
            MEMORY_WRITE_DOUBLE =
                    Shaded.class.getMethod(
                            "memoryWriteDouble", int.class, double.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_BYTE_READ =
                    Shaded.class.getMethod(
                            "memoryAtomicIntByteRead", int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_SHORT_READ =
                    Shaded.class.getMethod(
                            "memoryAtomicIntShortRead", int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_READ =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRead", int.class, int.class, Memory.class);
            MEMORY_ATOMIC_LONG_READ =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRead", int.class, int.class, Memory.class);
            MEMORY_ATOMIC_LONG_BYTE_READ =
                    Shaded.class.getMethod(
                            "memoryAtomicLongByteRead", int.class, int.class, Memory.class);
            MEMORY_ATOMIC_LONG_SHORT_READ =
                    Shaded.class.getMethod(
                            "memoryAtomicLongShortRead", int.class, int.class, Memory.class);
            MEMORY_ATOMIC_LONG_INT_READ =
                    Shaded.class.getMethod(
                            "memoryAtomicLongIntRead", int.class, int.class, Memory.class);
            I32_GE_U = Shaded.class.getMethod("i32_ge_u", int.class, int.class);
            REF_IS_NULL = Shaded.class.getMethod("isRefNull", int.class);
            GC_REF_IS_NULL = Shaded.class.getMethod("isGcRefNull", Object.class);
            REF_AS_NON_NULL = Shaded.class.getMethod("refAsNonNull", int.class);
            GC_REF_AS_NON_NULL = Shaded.class.getMethod("gcRefAsNonNull", Object.class);
            TABLE_GET = Shaded.class.getMethod("tableGet", int.class, int.class, Instance.class);
            TABLE_GET_REF =
                    Shaded.class.getMethod("tableGetRef", int.class, int.class, Instance.class);
            TABLE_SET =
                    Shaded.class.getMethod(
                            "tableSet", int.class, int.class, int.class, Instance.class);
            TABLE_SET_REF =
                    Shaded.class.getMethod(
                            "tableSetRef", int.class, Object.class, int.class, Instance.class);
            TABLE_SIZE = Shaded.class.getMethod("tableSize", int.class, Instance.class);
            TABLE_GROW =
                    Shaded.class.getMethod(
                            "tableGrow", int.class, int.class, int.class, Instance.class);
            TABLE_GROW_REF =
                    Shaded.class.getMethod(
                            "tableGrowRef", Object.class, int.class, int.class, Instance.class);
            TABLE_FILL =
                    Shaded.class.getMethod(
                            "tableFill",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            TABLE_FILL_REF =
                    Shaded.class.getMethod(
                            "tableFillRef",
                            int.class,
                            Object.class,
                            int.class,
                            int.class,
                            Instance.class);
            TABLE_COPY =
                    Shaded.class.getMethod(
                            "tableCopy",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            TABLE_INIT =
                    Shaded.class.getMethod(
                            "tableInit",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            TABLE_REQUIRED_REF = TableInstance.class.getMethod("requiredRef", int.class);
            TABLE_INSTANCE = TableInstance.class.getMethod("instance", int.class);
            THROW_CALL_STACK_EXHAUSTED =
                    Shaded.class.getMethod("throwCallStackExhausted", StackOverflowError.class);
            THROW_INDIRECT_CALL_TYPE_MISMATCH =
                    Shaded.class.getMethod("throwIndirectCallTypeMismatch");
            THROW_OUT_OF_BOUNDS_MEMORY_ACCESS =
                    Shaded.class.getMethod("throwOutOfBoundsMemoryAccess");
            THROW_TRAP_EXCEPTION = Shaded.class.getMethod("throwTrapException");
            THROW_NULL_FUNCTION_REFERENCE = Shaded.class.getMethod("throwNullFunctionReference");
            THROW_UNKNOWN_FUNCTION = Shaded.class.getMethod("throwUnknownFunction", int.class);

            AOT_INTERPRETER_MACHINE_CALL =
                    CompilerInterpreterMachine.class.getMethod("call", int.class, long[].class);

            // Exception handling methods
            CREATE_WASM_EXCEPTION =
                    Shaded.class.getMethod(
                            "createWasmException", long[].class, int.class, Instance.class);
            CREATE_WASM_EXCEPTION_GC =
                    Shaded.class.getMethod(
                            "createWasmExceptionGc",
                            long[].class,
                            Object[].class,
                            int.class,
                            Instance.class);
            INSTANCE_GET_EXCEPTION = Instance.class.getMethod("exn", int.class);
            EXCEPTION_MATCHES =
                    Shaded.class.getMethod(
                            "exceptionMatches", WasmException.class, int.class, Instance.class);

            MEMORY_ATOMIC_INT_WRITE =
                    Shaded.class.getMethod(
                            "memoryAtomicIntWrite", int.class, int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_BYTE_WRITE =
                    Shaded.class.getMethod(
                            "memoryAtomicIntByteWrite",
                            int.class,
                            byte.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_SHORT_WRITE =
                    Shaded.class.getMethod(
                            "memoryAtomicIntShortWrite",
                            int.class,
                            short.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_WRITE =
                    Shaded.class.getMethod(
                            "memoryAtomicLongWrite",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_BYTE_WRITE =
                    Shaded.class.getMethod(
                            "memoryAtomicLongByteWrite",
                            int.class,
                            byte.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_SHORT_WRITE =
                    Shaded.class.getMethod(
                            "memoryAtomicLongShortWrite",
                            int.class,
                            short.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_INT_WRITE =
                    Shaded.class.getMethod(
                            "memoryAtomicLongIntWrite",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW_ADD =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmwAdd", int.class, int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_RMW_SUB =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmwSub", int.class, int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_RMW_AND =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmwAnd", int.class, int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_RMW_OR =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmwOr", int.class, int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_RMW_XOR =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmwXor", int.class, int.class, int.class, Memory.class);
            MEMORY_ATOMIC_INT_RMW_XCHG =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmwXchg",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW_CMPXCHG =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmwCmpxchg",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);

            MEMORY_ATOMIC_INT_RMW8_ADD_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw8AddU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW8_SUB_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw8SubU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW8_AND_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw8AndU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW8_OR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw8OrU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW8_XOR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw8XorU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW8_XCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw8XchgU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW8_CMPXCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw8CmpxchgU",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW16_ADD_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw16AddU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW16_SUB_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw16SubU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW16_AND_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw16AndU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW16_OR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw16OrU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW16_XOR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw16XorU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW16_XCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw16XchgU",
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_INT_RMW16_CMPXCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicIntRmw16CmpxchgU",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW_ADD =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmwAdd",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW_SUB =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmwSub",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW_AND =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmwAnd",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW_OR =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmwOr",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW_XOR =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmwXor",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW_XCHG =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmwXchg",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW_CMPXCHG =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmwCmpxchg",
                            int.class,
                            long.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW8_ADD_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw8AddU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW8_SUB_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw8SubU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW8_AND_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw8AndU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW8_OR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw8OrU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW8_XOR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw8XorU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW8_XCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw8XchgU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW8_CMPXCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw8CmpxchgU",
                            int.class,
                            long.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW16_ADD_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw16AddU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW16_SUB_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw16SubU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW16_AND_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw16AndU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW16_OR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw16OrU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW16_XOR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw16XorU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW16_XCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw16XchgU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW16_CMPXCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw16CmpxchgU",
                            int.class,
                            long.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW32_ADD_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw32AddU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW32_SUB_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw32SubU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW32_AND_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw32AndU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW32_OR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw32OrU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW32_XOR_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw32XorU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW32_XCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw32XchgU",
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_LONG_RMW32_CMPXCHG_U =
                    Shaded.class.getMethod(
                            "memoryAtomicLongRmw32CmpxchgU",
                            int.class,
                            long.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_WAIT32 =
                    Shaded.class.getMethod(
                            "memoryAtomicWait32",
                            int.class,
                            int.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_WAIT64 =
                    Shaded.class.getMethod(
                            "memoryAtomicWait64",
                            int.class,
                            long.class,
                            long.class,
                            int.class,
                            Memory.class);
            MEMORY_ATOMIC_NOTIFY =
                    Shaded.class.getMethod(
                            "memoryAtomicNotify", int.class, int.class, int.class, Memory.class);
            MEMORY_ATOMIC_FENCE = Shaded.class.getMethod("memoryAtomicFence", Memory.class);

            // Tail calls
            SET_TAIL_CALL =
                    Shaded.class.getMethod("setTailCall", int.class, long[].class, Instance.class);
            SET_TAIL_CALL_WITH_REFS =
                    Shaded.class.getMethod(
                            "setTailCallWithRefs",
                            int.class,
                            long[].class,
                            Object[].class,
                            Instance.class);
            SET_TAIL_CALL_INDIRECT =
                    Shaded.class.getMethod(
                            "setTailCallIndirect",
                            long[].class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            SET_TAIL_CALL_INDIRECT_WITH_REFS =
                    Shaded.class.getMethod(
                            "setTailCallIndirectWithRefs",
                            long[].class,
                            Object[].class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            IS_TAIL_CALL_PENDING = Shaded.class.getMethod("isTailCallPending", Instance.class);
            RESOLVE_TAIL_CALL = Shaded.class.getMethod("resolveTailCall", Instance.class);
            RESOLVE_TAIL_CALL_WITH_REFS =
                    Shaded.class.getMethod("resolveTailCallWithRefs", Instance.class);

            // WithRefs overloads for cross-module/host calls
            CALL_HOST_FUNCTION_WITH_REFS =
                    Shaded.class.getMethod(
                            "callHostFunctionWithRefs",
                            Instance.class,
                            int.class,
                            long[].class,
                            Object[].class);
            CALL_INDIRECT_WITH_REFS =
                    Shaded.class.getMethod(
                            "callIndirectWithRefs",
                            long[].class,
                            Object[].class,
                            int.class,
                            int.class,
                            Instance.class);

            // GC
            STRUCT_NEW =
                    Shaded.class.getMethod(
                            "structNew", long[].class, Object[].class, int.class, Instance.class);
            STRUCT_NEW_DEFAULT =
                    Shaded.class.getMethod("structNewDefault", int.class, Instance.class);
            STRUCT_GET =
                    Shaded.class.getMethod(
                            "structGet", Object.class, int.class, int.class, Instance.class);
            STRUCT_GET_REF =
                    Shaded.class.getMethod(
                            "structGetRef", Object.class, int.class, int.class, Instance.class);
            STRUCT_GET_S =
                    Shaded.class.getMethod(
                            "structGetS", Object.class, int.class, int.class, Instance.class);
            STRUCT_GET_U =
                    Shaded.class.getMethod(
                            "structGetU", Object.class, int.class, int.class, Instance.class);
            STRUCT_SET =
                    Shaded.class.getMethod(
                            "structSet",
                            Object.class,
                            long.class,
                            int.class,
                            int.class,
                            Instance.class);
            STRUCT_SET_REF =
                    Shaded.class.getMethod(
                            "structSetRef",
                            Object.class,
                            Object.class,
                            int.class,
                            int.class,
                            Instance.class);
            ARRAY_NEW =
                    Shaded.class.getMethod(
                            "arrayNew", long.class, int.class, int.class, Instance.class);
            ARRAY_NEW_REF =
                    Shaded.class.getMethod(
                            "arrayNewRef", Object.class, int.class, int.class, Instance.class);
            ARRAY_NEW_DEFAULT =
                    Shaded.class.getMethod("arrayNewDefault", int.class, int.class, Instance.class);
            ARRAY_NEW_FIXED =
                    Shaded.class.getMethod(
                            "arrayNewFixed", long[].class, int.class, Instance.class);
            ARRAY_NEW_FIXED_REFS =
                    Shaded.class.getMethod(
                            "arrayNewFixedRefs", Object[].class, int.class, Instance.class);
            ARRAY_NEW_DATA =
                    Shaded.class.getMethod(
                            "arrayNewData",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            ARRAY_NEW_ELEM =
                    Shaded.class.getMethod(
                            "arrayNewElem",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            ARRAY_GET =
                    Shaded.class.getMethod(
                            "arrayGet", Object.class, int.class, int.class, Instance.class);
            ARRAY_GET_REF =
                    Shaded.class.getMethod(
                            "arrayGetRef", Object.class, int.class, int.class, Instance.class);
            ARRAY_GET_S =
                    Shaded.class.getMethod(
                            "arrayGetS", Object.class, int.class, int.class, Instance.class);
            ARRAY_GET_U =
                    Shaded.class.getMethod(
                            "arrayGetU", Object.class, int.class, int.class, Instance.class);
            ARRAY_SET =
                    Shaded.class.getMethod(
                            "arraySet",
                            Object.class,
                            int.class,
                            long.class,
                            int.class,
                            Instance.class);
            ARRAY_SET_REF =
                    Shaded.class.getMethod(
                            "arraySetRef",
                            Object.class,
                            int.class,
                            Object.class,
                            int.class,
                            Instance.class);
            ARRAY_LEN = Shaded.class.getMethod("arrayLen", Object.class, Instance.class);
            ARRAY_FILL =
                    Shaded.class.getMethod(
                            "arrayFill",
                            Object.class,
                            int.class,
                            long.class,
                            int.class,
                            int.class,
                            Instance.class);
            ARRAY_FILL_REF =
                    Shaded.class.getMethod(
                            "arrayFillRef",
                            Object.class,
                            int.class,
                            Object.class,
                            int.class,
                            int.class,
                            Instance.class);
            ARRAY_COPY =
                    Shaded.class.getMethod(
                            "arrayCopy",
                            Object.class,
                            int.class,
                            Object.class,
                            int.class,
                            int.class,
                            Instance.class);
            ARRAY_INIT_DATA =
                    Shaded.class.getMethod(
                            "arrayInitData",
                            Object.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            ARRAY_INIT_ELEM =
                    Shaded.class.getMethod(
                            "arrayInitElem",
                            Object.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Instance.class);
            REF_TEST =
                    Shaded.class.getMethod(
                            "refTest", Object.class, int.class, int.class, Instance.class);
            REF_TEST_NULL =
                    Shaded.class.getMethod(
                            "refTestNull", Object.class, int.class, int.class, Instance.class);
            CAST_TEST =
                    Shaded.class.getMethod(
                            "castTest", Object.class, int.class, int.class, Instance.class);
            CAST_TEST_NULL =
                    Shaded.class.getMethod(
                            "castTestNull", Object.class, int.class, int.class, Instance.class);
            HEAP_TYPE_MATCH =
                    Shaded.class.getMethod(
                            "heapTypeMatch",
                            Object.class,
                            boolean.class,
                            int.class,
                            int.class,
                            Instance.class);
            REF_TEST_INT =
                    Shaded.class.getMethod(
                            "refTestInt", int.class, int.class, int.class, Instance.class);
            REF_TEST_NULL_INT =
                    Shaded.class.getMethod(
                            "refTestNullInt", int.class, int.class, int.class, Instance.class);
            CAST_TEST_INT =
                    Shaded.class.getMethod(
                            "castTestInt", int.class, int.class, int.class, Instance.class);
            CAST_TEST_NULL_INT =
                    Shaded.class.getMethod(
                            "castTestNullInt", int.class, int.class, int.class, Instance.class);
            HEAP_TYPE_MATCH_INT =
                    Shaded.class.getMethod(
                            "heapTypeMatchInt",
                            int.class,
                            boolean.class,
                            int.class,
                            int.class,
                            Instance.class);
            REF_EQ = Shaded.class.getMethod("refEq", Object.class, Object.class);
            REF_I31 = Shaded.class.getMethod("refI31", int.class);
            I31_GET_S = Shaded.class.getMethod("i31GetS", Object.class);
            I31_GET_U = Shaded.class.getMethod("i31GetU", Object.class);
            ANY_CONVERT_EXTERN = Shaded.class.getMethod("anyConvertExtern", Object.class);
            EXTERN_CONVERT_ANY = Shaded.class.getMethod("externConvertAny", Object.class);
            DATA_DROP = Shaded.class.getMethod("dataDrop", int.class, Instance.class);

        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private ShadedRefs() {}
}
