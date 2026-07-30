package run.endive.wasm.types;

/**
 * Encodes the three distinct forms of a WebAssembly block type in an instruction operand.
 *
 * <p>The binary format uses a signed {@code s33}: negative values denote inline value types,
 * {@code -0x40} denotes the empty block type, and non-negative values denote type-section
 * indices. These forms must remain distinct after decoding because the raw value-type opcodes
 * overlap valid type indices.
 */
public final class BlockType {
    private static final long KIND_MASK = 0xc000_0000_0000_0000L;
    private static final long VALUE_TYPE_KIND = 0x4000_0000_0000_0000L;
    private static final long EMPTY_KIND = 0x8000_0000_0000_0000L;
    private static final long TYPE_INDEX_MASK = 0xffff_ffffL;
    private static final long VALUE_TYPE_PAYLOAD_MASK = 0xffff_ffffffL;

    private BlockType() {}

    public static long empty() {
        return EMPTY_KIND;
    }

    public static long forTypeIndex(long typeIndex) {
        if (typeIndex < 0 || typeIndex > TYPE_INDEX_MASK) {
            throw new IllegalArgumentException("block type index must be an unsigned 32-bit value");
        }
        return typeIndex;
    }

    public static long forValueType(long valueTypeId) {
        if (!ValType.isValid(valueTypeId)) {
            throw new IllegalArgumentException("invalid block value type");
        }

        var valueType = ValType.builder().fromId(valueTypeId).build();
        var typeIndex = Integer.toUnsignedLong(valueType.typeIdx());
        return VALUE_TYPE_KIND | (typeIndex << 8) | valueType.opcode();
    }

    public static boolean isEmpty(long blockType) {
        return blockType == EMPTY_KIND;
    }

    public static boolean isTypeIndex(long blockType) {
        return (blockType & KIND_MASK) == 0 && blockType <= TYPE_INDEX_MASK;
    }

    public static boolean isValueType(long blockType) {
        return (blockType & KIND_MASK) == VALUE_TYPE_KIND
                && (blockType & ~(KIND_MASK | VALUE_TYPE_PAYLOAD_MASK)) == 0;
    }

    public static long typeIndex(long blockType) {
        if (!isTypeIndex(blockType)) {
            throw new IllegalArgumentException("block type is not a type index");
        }
        return blockType;
    }

    public static long valueTypeId(long blockType) {
        if (!isValueType(blockType)) {
            throw new IllegalArgumentException("block type is not a value type");
        }

        var opcode = (int) (blockType & 0xff);
        var typeIndex = (int) ((blockType >>> 8) & TYPE_INDEX_MASK);
        return ValType.builder().withOpcode(opcode).withTypeIdx(typeIndex).id();
    }
}
