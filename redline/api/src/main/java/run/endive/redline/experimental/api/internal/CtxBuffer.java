package run.endive.redline.experimental.api.internal;

/**
 * Layout of the shared context buffer passed between Java and native compiled code.
 *
 * <pre>
 * Offset  Type   Field             Description
 * ------  -----  ----------------  ------------------------------------------
 *   0     i64    funcTablePtr      Pointer to function pointer table
 *   8     i64    trampolinePtr     Upcall stub for CALL_INDIRECT fallback
 *  16     i32    trapCode          Trap code written by native pre-checks
 *  20     i32    typeId            CALL_INDIRECT: expected type index
 *  24     i32    tableIdx          CALL_INDIRECT: table index
 *  28     i32    elemIdx           CALL_INDIRECT: table element index
 *  32     i32    argCount          Number of call arguments
 *  36     i32    memGrowDelta      Page count delta for memory.grow
 *  40     i64    argsPtr           Pointer to separate args buffer
 *  48     i64    stackLimit        Stack pointer limit for call depth guard
 *  56     i64    memmovePtr        Pointer to libc memmove
 *  64     i64    memsetPtr         Pointer to libc memset
 *  72     i64    interruptFlag     Non-zero = interrupt requested
 * 200     i64    globalsPtr        Pointer to globals buffer
 * 208     i64    memGrowPtr        Upcall stub for memory.grow
 * 216     i32    memoryPages       Current memory page count
 * 224     i64    memBaseAddr       Current memory base address
 * 232     i64    tablePtrs         Pointer to array of table buffer pointers
 * 240     i64    funcTypesPtr      Pointer to funcTypes array (i32 per func)
 * ------  -----  ----------------  ------------------------------------------
 * Total: 248 bytes used, 256 allocated (CTX_SIZE)
 * </pre>
 */
public final class CtxBuffer {

    private CtxBuffer() {}

    public static final int CTX_SIZE = 256;
    public static final int ARGS_BUFFER_CAPACITY = 1024;

    public static final int FUNC_TABLE_PTR = 0;
    public static final int TRAMPOLINE_PTR = 8;

    public static final int TRAP_CODE = 16;
    public static final int TYPE_ID = 20;
    public static final int TABLE_IDX = 24;
    public static final int ELEM_IDX = 28;
    public static final int ARG_COUNT = 32;
    public static final int MEM_GROW_DELTA = 36;
    public static final int ARGS_PTR = 40;
    public static final int STACK_LIMIT = 48;
    public static final int MEMMOVE_PTR = 56;
    public static final int MEMSET_PTR = 64;
    public static final int INTERRUPT_FLAG = 72;

    public static final int GLOBALS_PTR = 200;
    public static final int MEM_GROW_PTR = 208;
    public static final int MEMORY_PAGES = 216;
    public static final int MEM_BASE_ADDR = 224;
    public static final int TABLE_PTRS = 232;
    public static final int FUNC_TYPES_PTR = 240;

    public static final int TRAP_NONE = 0;
    public static final int TRAP_DIV_BY_ZERO = 1;
    public static final int TRAP_INT_OVERFLOW = 2;
    public static final int TRAP_UNREACHABLE = 3;
    public static final int TRAP_TRUNC_OVERFLOW = 4;
    public static final int TRAP_OOB = 5;
    public static final int TRAP_CALL_STACK_EXHAUSTED = 6;
    public static final int TRAP_TABLE_OOB = 7;
    public static final int TRAP_UNDEFINED_ELEMENT = 8;
    public static final int TRAP_INDIRECT_CALL_TYPE_MISMATCH = 9;
    public static final int TRAP_UNINITIALIZED_ELEMENT = 10;
    public static final int TRAP_TRUNC_NAN = 11;
    public static final int TRAP_UNALIGNED_ATOMIC = 12;
    public static final int TRAP_INTERRUPTED = 13;

    public static final int TABLE_SIZE_OFFSET = 0;
    public static final int TABLE_MAX_OFFSET = 4;
    public static final int TABLE_ENTRIES_OFFSET = 8;
    public static final int TABLE_ENTRY_SIZE = 16;
    public static final int ENTRY_TYPE_IDX_OFFSET = 0;
    public static final int ENTRY_FUNC_ID_OFFSET = 4;
    public static final int ENTRY_FUNC_PTR_OFFSET = 8;

    public static int argOffset(int i) {
        return 8 * i;
    }
}
