package run.endive.redline.experimental.api;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serializes/deserializes pre-compiled native code (byte[][]).
 *
 * <p>Format:
 * <pre>
 *   [4 bytes: magic "CL4J"]
 *   [4 bytes: version (1)]
 *   [4 bytes: function count]
 *   For each function:
 *     [4 bytes: code length, 0 for null/uncompiled]
 *     [N bytes: native code]
 * </pre>
 */
public final class NativeCodeSerializer {

    private static final int MAGIC = 0x434C344A; // "CL4J"
    private static final int VERSION = 1;

    private NativeCodeSerializer() {}

    public static void serialize(byte[][] code, OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        dos.writeInt(code.length);
        for (byte[] func : code) {
            if (func != null) {
                dos.writeInt(func.length);
                dos.write(func);
            } else {
                dos.writeInt(0);
            }
        }
        dos.flush();
    }

    public static byte[][] deserialize(InputStream in) throws IOException {
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
        int count = dis.readInt();
        byte[][] code = new byte[count][];
        for (int i = 0; i < count; i++) {
            int len = dis.readInt();
            if (len > 0) {
                code[i] = dis.readNBytes(len);
                if (code[i].length != len) {
                    throw new IOException(
                            "Truncated native code for function "
                                    + i
                                    + ": expected "
                                    + len
                                    + " bytes, got "
                                    + code[i].length);
                }
            }
        }
        return code;
    }
}
