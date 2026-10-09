package run.endive.redline.experimental.api;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Serializes/deserializes pre-compiled {@link NativeCode}.
 *
 * <p>Format:
 * <pre>
 *   [4 bytes: magic "CL4J"]
 *   [4 bytes: version (3)]
 *   [modified UTF-8: target triple]
 *   [4 bytes: image length]
 *   [N bytes: image]
 *   [4 bytes: function body count]
 *   For each function body:
 *     [4 bytes: body offset, -1 for uncompiled]
 *     [4 bytes: entry trampoline offset, -1 for uncompiled]
 *   [4 bytes: imported function count]
 *   For each imported function:
 *     [4 bytes: import trampoline offset]
 *     [4 bytes: offset of the slot for its host stub address]
 * </pre>
 */
public final class NativeCodeSerializer {

    private static final int MAGIC = 0x434C344A; // "CL4J"
    private static final int VERSION = 3;

    private NativeCodeSerializer() {}

    public static void serialize(NativeCode code, OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        dos.writeUTF(code.triple());
        dos.writeInt(code.image().length);
        dos.write(code.image());
        dos.writeInt(code.functionBodyCount());
        for (int i = 0; i < code.functionBodyCount(); i++) {
            dos.writeInt(code.bodyOffset(i));
            dos.writeInt(code.entryTrampolineOffset(i));
        }
        dos.writeInt(code.importCount());
        for (int i = 0; i < code.importCount(); i++) {
            dos.writeInt(code.importTrampolineOffset(i));
            dos.writeInt(code.importStubSlotOffset(i));
        }
        dos.flush();
    }

    public static NativeCode deserialize(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != MAGIC) {
            throw new IOException(
                    "Invalid native code file: bad magic 0x" + Integer.toHexString(magic));
        }
        int version = dis.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported native code version: " + version);
        }
        String triple = dis.readUTF();
        byte[] image = readBytes(dis, readCount(dis, "image length"), "image");
        int bodyCount = readCount(dis, "function body count");
        // Read before allocating, so a corrupt count fails on the truncated file
        int[] offsets = readInts(dis, bodyCount * 2L, "function body offsets");
        int[] bodyOffsets = new int[bodyCount];
        int[] entryTrampolineOffsets = new int[bodyCount];
        for (int i = 0; i < bodyCount; i++) {
            bodyOffsets[i] = offsets[i * 2];
            entryTrampolineOffsets[i] = offsets[i * 2 + 1];
        }
        int importCount = readCount(dis, "imported function count");
        int[] importOffsets = readInts(dis, importCount * 2L, "import offsets");
        int[] importTrampolineOffsets = new int[importCount];
        int[] importStubSlotOffsets = new int[importCount];
        for (int i = 0; i < importCount; i++) {
            importTrampolineOffsets[i] = importOffsets[i * 2];
            importStubSlotOffsets[i] = importOffsets[i * 2 + 1];
        }
        try {
            return new NativeCode(
                    triple,
                    image,
                    bodyOffsets,
                    entryTrampolineOffsets,
                    importTrampolineOffsets,
                    importStubSlotOffsets);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid native code file: " + e.getMessage(), e);
        }
    }

    private static int readCount(DataInputStream dis, String what) throws IOException {
        int count = dis.readInt();
        if (count < 0) {
            throw new IOException("Invalid native code file: negative " + what + " " + count);
        }
        return count;
    }

    private static byte[] readBytes(DataInputStream dis, int len, String what) throws IOException {
        byte[] bytes = dis.readNBytes(len);
        if (bytes.length != len) {
            throw new IOException(
                    "Truncated native code "
                            + what
                            + ": expected "
                            + len
                            + " bytes, got "
                            + bytes.length);
        }
        return bytes;
    }

    private static int[] readInts(DataInputStream dis, long count, String what) throws IOException {
        if (count > Integer.MAX_VALUE / 4) {
            throw new IOException("Invalid native code file: too many " + what);
        }
        byte[] bytes = readBytes(dis, (int) count * 4, what);
        int[] ints = new int[(int) count];
        ByteBuffer.wrap(bytes).asIntBuffer().get(ints);
        return ints;
    }
}
