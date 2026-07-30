package run.endive.corpus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds small Wasm modules for block-type regression tests. */
public final class BlockTypeTestModule {
    public static final int FIRST_TYPE_INDEX = 63;
    public static final int LAST_TYPE_INDEX = 128;

    private static final int FUNCTION_COUNT = LAST_TYPE_INDEX - FIRST_TYPE_INDEX + 1;

    private BlockTypeTestModule() {}

    /**
     * Returns a module with one exported function for each type index from 63 through 128.
     *
     * <p>Every type has signature {@code () -> i32}. Each function contains a block whose type is
     * referenced by the corresponding type-section index and returns that index. This range covers
     * the boundary at index 64 and every index that overlaps a one-byte value-type opcode.
     */
    public static byte[] create() {
        var module = new ByteArrayOutputStream();
        module.write(0x00);
        module.write(0x61);
        module.write(0x73);
        module.write(0x6d);
        module.write(0x01);
        module.write(0x00);
        module.write(0x00);
        module.write(0x00);

        writeTypeSection(module);
        writeFunctionSection(module);
        writeExportSection(module);
        writeCodeSection(module);
        return module.toByteArray();
    }

    public static String exportName(int typeIndex) {
        if (typeIndex < FIRST_TYPE_INDEX || typeIndex > LAST_TYPE_INDEX) {
            throw new IllegalArgumentException("type index outside the test module range");
        }
        return "type" + typeIndex;
    }

    private static void writeTypeSection(ByteArrayOutputStream module) {
        var section = new ByteArrayOutputStream();
        writeUnsignedLeb(section, LAST_TYPE_INDEX + 1L);
        for (var i = 0; i <= LAST_TYPE_INDEX; i++) {
            section.write(0x60);
            section.write(0x00);
            section.write(0x01);
            section.write(0x7f);
        }
        writeSection(module, 1, section);
    }

    private static void writeFunctionSection(ByteArrayOutputStream module) {
        var section = new ByteArrayOutputStream();
        writeUnsignedLeb(section, FUNCTION_COUNT);
        for (var i = 0; i < FUNCTION_COUNT; i++) {
            writeUnsignedLeb(section, 0);
        }
        writeSection(module, 3, section);
    }

    private static void writeExportSection(ByteArrayOutputStream module) {
        var section = new ByteArrayOutputStream();
        writeUnsignedLeb(section, FUNCTION_COUNT);
        for (var typeIndex = FIRST_TYPE_INDEX; typeIndex <= LAST_TYPE_INDEX; typeIndex++) {
            var name = exportName(typeIndex).getBytes(StandardCharsets.UTF_8);
            writeUnsignedLeb(section, name.length);
            writeAll(section, name);
            section.write(0x00);
            writeUnsignedLeb(section, typeIndex - FIRST_TYPE_INDEX);
        }
        writeSection(module, 7, section);
    }

    private static void writeCodeSection(ByteArrayOutputStream module) {
        var section = new ByteArrayOutputStream();
        writeUnsignedLeb(section, FUNCTION_COUNT);
        for (var typeIndex = FIRST_TYPE_INDEX; typeIndex <= LAST_TYPE_INDEX; typeIndex++) {
            var body = new ByteArrayOutputStream();
            body.write(0x00);
            body.write(0x02);
            writeSignedLeb(body, typeIndex);
            body.write(0x41);
            writeSignedLeb(body, typeIndex);
            body.write(0x0b);
            body.write(0x0b);

            writeUnsignedLeb(section, body.size());
            writeAll(section, body.toByteArray());
        }
        writeSection(module, 10, section);
    }

    private static void writeSection(
            ByteArrayOutputStream module, int sectionId, ByteArrayOutputStream section) {
        module.write(sectionId);
        writeUnsignedLeb(module, section.size());
        writeAll(module, section.toByteArray());
    }

    /**
     * Appends every byte of {@code bytes} to {@code output}.
     *
     * <p>{@link ByteArrayOutputStream#writeBytes(byte[])} is Java 11 API that is missing from older
     * Android runtimes, so the three-argument {@code write} is used instead.
     */
    private static void writeAll(ByteArrayOutputStream output, byte[] bytes) {
        output.write(bytes, 0, bytes.length);
    }

    private static void writeUnsignedLeb(ByteArrayOutputStream output, long value) {
        do {
            var next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (value != 0);
    }

    private static void writeSignedLeb(ByteArrayOutputStream output, long value) {
        boolean more;
        do {
            var next = (int) (value & 0x7f);
            value >>= 7;
            var signBitSet = (next & 0x40) != 0;
            more = !((value == 0 && !signBitSet) || (value == -1 && signBitSet));
            if (more) {
                next |= 0x80;
            }
            output.write(next);
        } while (more);
    }
}
