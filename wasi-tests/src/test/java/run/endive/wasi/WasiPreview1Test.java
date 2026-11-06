package run.endive.wasi;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createSymbolicLink;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.getLastModifiedTime;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.writeString;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static run.endive.wasi.Files.copyDirectory;

import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import run.endive.runtime.ByteBufferMemory;
import run.endive.wasm.types.MemoryLimits;

@Timeout(10)
public class WasiPreview1Test {

    @Test
    public void wasiRandom() {
        var seed = 0x12345678;
        var wasi =
                WasiPreview1.builder()
                        .withOptions(WasiOptions.builder().withRandom(new Random(seed)).build())
                        .build();

        var memory = new ByteBufferMemory(new MemoryLimits(8, 8));
        assertEquals(0, wasi.randomGet(memory, 0, 123_456));
        assertEquals(0, wasi.randomGet(memory, 222_222, 87_654));

        var random = new Random(seed);
        byte[] first = new byte[123_456];
        random.nextBytes(first);
        byte[] second = new byte[87_654];
        random.nextBytes(second);

        assertArrayEquals(first, memory.readBytes(0, 123_456));
        assertArrayEquals(second, memory.readBytes(222_222, 87_654));
    }

    @Test
    public void wasiPositionedWriteWithAppendShouldFail() throws IOException {
        try (var fs = newZeroFs()) {
            var dir = "fs-tests.dir";
            Path source =
                    new File("../wasi-testsuite/tests/c/testsuite/wasm32-wasip1")
                            .toPath()
                            .resolve(dir);
            Path target = fs.getPath(dir);
            copyDirectory(source, target);

            try (var wasi = wasiWithDirectory(target.toString(), target)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));

                int fdPtr = 0;
                int result =
                        wasi.pathOpen(
                                memory,
                                3,
                                WasiLookupFlags.SYMLINK_FOLLOW,
                                "pwrite.cleanup",
                                WasiOpenFlags.CREAT,
                                0,
                                0,
                                WasiFdFlags.APPEND,
                                fdPtr);
                assertEquals(WasiErrno.ESUCCESS.value(), result);

                int fd = memory.readInt(fdPtr);
                assertEquals(4, fd);

                result = wasi.fdPwrite(memory, fd, 0, 0, 0, 0);
                assertEquals(WasiErrno.ENOTSUP.value(), result);
            }
        }
    }

    @Test
    public void wasiReadLink() throws IOException {
        try (var fs = newZeroFs()) {
            Path root = fs.getPath("test");
            createDirectory(root);
            createSymbolicLink(root.resolve("abc"), fs.getPath("xyz"));
            try (var wasi = wasiWithDirectory(root.toString(), root)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));
                int bufPtr = 0;
                int bufUsedPtr = 16;

                int result = wasi.pathReadlink(memory, 3, "abc", bufPtr, 16, bufUsedPtr);
                assertEquals(WasiErrno.ESUCCESS.value(), result);

                int length = memory.readInt(bufUsedPtr);
                assertEquals(3, length);
                String name = memory.readString(bufPtr, length);
                assertEquals("xyz", name);
            }
        }
    }

    @Test
    public void fdReaddirDoesNotFollowSymlinks() throws IOException {
        try (var fs = newZeroFs()) {
            Path root = fs.getPath("test");
            createDirectory(root);
            writeString(root.resolve("file"), "hello");
            createDirectory(root.resolve("dir"));
            createSymbolicLink(root.resolve("link"), fs.getPath("file"));
            createSymbolicLink(root.resolve("dangling"), fs.getPath("missing"));
            try (var wasi = wasiWithDirectory(root.toString(), root)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));
                int buf = 0;
                int bufLen = 1024;
                int bufUsedPtr = 2048;

                int result = wasi.fdReaddir(memory, 3, buf, bufLen, 0, bufUsedPtr);
                assertEquals(WasiErrno.ESUCCESS.value(), result);

                Map<String, Integer> types = new HashMap<>();
                int used = memory.readInt(bufUsedPtr);
                for (int pos = buf; pos < buf + used; ) {
                    int nameLen = memory.readInt(pos + 16);
                    int type = memory.read(pos + 20);
                    types.put(memory.readString(pos + 24, nameLen), type);
                    pos += 24 + nameLen;
                }

                assertEquals(WasiFileType.REGULAR_FILE.value(), types.get("file"));
                assertEquals(WasiFileType.DIRECTORY.value(), types.get("dir"));
                assertEquals(WasiFileType.SYMBOLIC_LINK.value(), types.get("link"));
                assertEquals(WasiFileType.SYMBOLIC_LINK.value(), types.get("dangling"));
            }
        }
    }

    @Test
    public void wasiPollOneoffNoSubscriptions() {
        var wasi = WasiPreview1.builder().build();
        var memory = new ByteBufferMemory(new MemoryLimits(0));
        int result = wasi.pollOneoff(memory, 0, 0, 0, 0);
        assertEquals(WasiErrno.EINVAL.value(), result);
    }

    @Test
    public void wasiPollOneoffStdinNoData() {
        var stdin = new ByteArrayInputStream("".getBytes(UTF_8));
        var wasiOpts = WasiOptions.builder().withStdin(stdin).build();
        var wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
        var memory = new ByteBufferMemory(new MemoryLimits(1));
        int nsubscriptions = 2;
        int neventsPtr = 0;
        int inPtr = 4;
        int outPtr = inPtr + nsubscriptions * 48;
        int in = inPtr;
        memory.writeLong(in, 0x8888_7777_6666_5555L); // userdata
        memory.writeByte(in + 8, WasiEventType.CLOCK);
        memory.writeI32(in + 16, WasiClockId.REALTIME);
        memory.writeLong(in + 16 + 8, MILLISECONDS.toNanos(200));
        memory.writeLong(in + 16 + 16, 0); // precision
        memory.writeShort(in + 16 + 24, (short) 0);
        in += 48;
        memory.writeLong(in, 0xAAAA_BBBB_CCCC_DDDDL); // userdata
        memory.writeByte(in + 8, WasiEventType.FD_READ);
        memory.writeI32(in + 16, 0); // fd
        int result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr);
        assertEquals(WasiErrno.ESUCCESS.value(), result);
        assertEquals(1, memory.readInt(neventsPtr));
        assertEquals(0x8888_7777_6666_5555L, memory.readLong(outPtr));
        assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(outPtr + 8));
        assertEquals(WasiEventType.CLOCK, memory.read(outPtr + 10));
    }

    @Test
    public void wasPollOneoffStdinWithData() {
        var stdin = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
        var wasiOpts = WasiOptions.builder().withStdin(stdin).build();
        var wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
        var memory = new ByteBufferMemory(new MemoryLimits(1));
        int nsubscriptions = 1;
        int neventsPtr = 0;
        int inPtr = 4;
        int outPtr = inPtr + nsubscriptions * 48;
        int in = inPtr;
        memory.writeLong(in, 0x8888_7777_6666_5555L); // userdata
        memory.writeByte(in + 8, WasiEventType.FD_READ);
        memory.writeI32(in + 16, 0); // fd
        int result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr);
        assertEquals(WasiErrno.ESUCCESS.value(), result);
        assertEquals(1, memory.readInt(neventsPtr));
        assertEquals(0x8888_7777_6666_5555L, memory.readLong(outPtr));
        assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(outPtr + 8));
        assertEquals(WasiEventType.FD_READ, memory.read(outPtr + 10));
    }

    @Test
    public void wasiPollOneoffRegularFile() {
        try (var fs = newZeroFs()) {
            Path root = fs.getPath("test");
            createDirectory(root);
            var file = root.resolve("hello.txt");
            writeString(file, "Hello, World!");
            try (var wasi = wasiWithDirectory(root.toString(), root)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));
                int fdPtr = 1024;
                int result = wasi.pathOpen(memory, 3, 0, "hello.txt", 0, 0, 0, 0, fdPtr);
                assertEquals(WasiErrno.ESUCCESS.value(), result);
                int fd = memory.readInt(fdPtr);
                assertEquals(4, fd);

                int nsubscriptions = 2;
                int neventsPtr = 0;
                int inPtr = 4;
                int outPtr = inPtr + nsubscriptions * 48;
                int in = inPtr;
                memory.writeLong(in, 0x8888_7777_6666_5555L); // userdata
                memory.writeByte(in + 8, WasiEventType.FD_READ);
                memory.writeI32(in + 16, fd);
                in += 48;
                memory.writeLong(in, 0xAAAA_BBBB_CCCC_DDDDL); // userdata
                memory.writeByte(in + 8, WasiEventType.FD_WRITE);
                memory.writeI32(in + 16, fd);
                result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr);
                assertEquals(WasiErrno.ESUCCESS.value(), result);
                assertEquals(2, memory.readInt(neventsPtr));
                int out = outPtr;
                assertEquals(0x8888_7777_6666_5555L, memory.readLong(out));
                assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(out + 8));
                assertEquals(WasiEventType.FD_READ, memory.read(out + 10));
                out += 32;
                assertEquals(0xAAAA_BBBB_CCCC_DDDDL, memory.readLong(out));
                assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(out + 8));
                assertEquals(WasiEventType.FD_WRITE, memory.read(out + 10));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void wasiPollOneoffClockAbstime() {
        long deadline = System.nanoTime() + MILLISECONDS.toNanos(25);
        var wasi = WasiPreview1.builder().build();
        var memory = new ByteBufferMemory(new MemoryLimits(1));
        int nsubscriptions = 1;
        int neventsPtr = 0;
        int inPtr = 4;
        int outPtr = inPtr + nsubscriptions * 48;
        int in = inPtr;
        memory.writeLong(in, 0x8888_7777_6666_5555L); // userdata
        memory.writeByte(in + 8, WasiEventType.CLOCK);
        memory.writeI32(in + 16, WasiClockId.MONOTONIC);
        memory.writeLong(in + 16 + 8, deadline);
        memory.writeLong(in + 16 + 16, 0); // precision
        memory.writeShort(in + 16 + 24, (short) WasiSubClockFlags.SUBSCRIPTION_CLOCK_ABSTIME);
        int result = wasi.pollOneoff(memory, inPtr, outPtr, nsubscriptions, neventsPtr);
        assertEquals(WasiErrno.ESUCCESS.value(), result);
        assertEquals(1, memory.readInt(neventsPtr));
        assertEquals(0x8888_7777_6666_5555L, memory.readLong(outPtr));
        assertEquals(WasiErrno.ESUCCESS.value(), memory.readShort(outPtr + 8));
        assertEquals(WasiEventType.CLOCK, memory.read(outPtr + 10));
        assertTrue(System.nanoTime() >= deadline);
    }

    @Test
    public void fdReadNegativeIovLenShouldReturnEinval() throws IOException {
        try (var fs = newZeroFs()) {
            Path root = fs.getPath("test");
            createDirectory(root);
            writeString(root.resolve("hello.txt"), "Hello");
            try (var wasi = wasiWithDirectory(root.toString(), root)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));

                int fdPtr = 0;
                int result = wasi.pathOpen(memory, 3, 0, "hello.txt", 0, 0, 0, 0, fdPtr);
                assertEquals(WasiErrno.ESUCCESS.value(), result);
                int fd = memory.readInt(fdPtr);

                int iovs = 100;
                int iovBase = 200;
                memory.writeI32(iovs, iovBase);
                memory.writeI32(iovs + 4, 0x80000001); // negative in signed int
                int nreadPtr = 300;
                result = wasi.fdRead(memory, fd, iovs, 1, nreadPtr);
                assertEquals(WasiErrno.EINVAL.value(), result);
            }
        }
    }

    @Test
    public void pathOpenReadWriteAppend(@TempDir Path dir) throws IOException {
        writeString(dir.resolve("log.txt"), "old");
        try (var wasi = wasiWithDirectory(dir.toString(), dir)) {
            var memory = new ByteBufferMemory(new MemoryLimits(1));
            long rights = WasiRights.FD_READ | WasiRights.FD_WRITE | WasiRights.FD_SEEK;
            int fd = openFile(wasi, memory, "log.txt", 0, rights, WasiFdFlags.APPEND);

            writeData(wasi, memory, fd, "new");
            seek(wasi, memory, fd, 0);
            assertEquals("oldnew", readData(wasi, memory, fd));

            seek(wasi, memory, fd, 0);
            writeData(wasi, memory, fd, "!");
        }
        assertEquals("oldnew!", java.nio.file.Files.readString(dir.resolve("log.txt")));
    }

    @Test
    public void pathOpenTruncateAppend(@TempDir Path dir) throws IOException {
        writeString(dir.resolve("read-write.txt"), "old");
        writeString(dir.resolve("write-only.txt"), "old");
        try (var wasi = wasiWithDirectory(dir.toString(), dir)) {
            var memory = new ByteBufferMemory(new MemoryLimits(1));
            long readWrite = WasiRights.FD_READ | WasiRights.FD_WRITE;
            int fd =
                    openFile(
                            wasi,
                            memory,
                            "read-write.txt",
                            WasiOpenFlags.TRUNC,
                            readWrite,
                            WasiFdFlags.APPEND);
            writeData(wasi, memory, fd, "new");

            fd =
                    openFile(
                            wasi,
                            memory,
                            "write-only.txt",
                            WasiOpenFlags.TRUNC,
                            WasiRights.FD_WRITE,
                            WasiFdFlags.APPEND);
            writeData(wasi, memory, fd, "new");
        }
        assertEquals("new", java.nio.file.Files.readString(dir.resolve("read-write.txt")));
        assertEquals("new", java.nio.file.Files.readString(dir.resolve("write-only.txt")));
    }

    @Test
    public void pathOpenEmptyPathReturnsEnoent() throws IOException {
        try (var fs = newZeroFs()) {
            Path root = fs.getPath("test");
            createDirectory(root);
            try (var wasi = wasiWithDirectory(root.toString(), root)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));
                int result = wasi.pathOpen(memory, 3, 0, "", 0, 0, 0, 0, 0);
                assertEquals(WasiErrno.ENOENT.value(), result);
            }
        }
    }

    private static int openFile(
            WasiPreview1 wasi,
            ByteBufferMemory memory,
            String path,
            int openFlags,
            long rights,
            int fdFlags) {
        int fdPtr = 0;
        int result = wasi.pathOpen(memory, 3, 0, path, openFlags, rights, 0, fdFlags, fdPtr);
        assertEquals(WasiErrno.ESUCCESS.value(), result);
        return memory.readInt(fdPtr);
    }

    private static void writeData(WasiPreview1 wasi, ByteBufferMemory memory, int fd, String data) {
        byte[] bytes = data.getBytes(UTF_8);
        memory.write(200, bytes);
        memory.writeI32(100, 200);
        memory.writeI32(104, bytes.length);
        assertEquals(WasiErrno.ESUCCESS.value(), wasi.fdWrite(memory, fd, 100, 1, 300));
        assertEquals(bytes.length, memory.readInt(300));
    }

    private static String readData(WasiPreview1 wasi, ByteBufferMemory memory, int fd) {
        memory.writeI32(100, 200);
        memory.writeI32(104, 64);
        assertEquals(WasiErrno.ESUCCESS.value(), wasi.fdRead(memory, fd, 100, 1, 300));
        return new String(memory.readBytes(200, memory.readInt(300)), UTF_8);
    }

    private static void seek(WasiPreview1 wasi, ByteBufferMemory memory, int fd, long offset) {
        assertEquals(
                WasiErrno.ESUCCESS.value(), wasi.fdSeek(memory, fd, offset, WasiWhence.SET, 300));
    }

    @Test
    public void pathFilestatSetTimesHonoursLookupFlags() throws IOException {
        try (var fs = newZeroFs()) {
            Path root = fs.getPath("test");
            createDirectory(root);
            writeString(root.resolve("file"), "hello");
            createSymbolicLink(root.resolve("link"), fs.getPath("file"));
            long fileTime = getLastModifiedTime(root.resolve("file")).toMillis();
            try (var wasi = wasiWithDirectory(root.toString(), root)) {
                int result =
                        wasi.pathFilestatSetTimes(
                                3, 0, "link", 0, SECONDS.toNanos(1), WasiFstFlags.MTIM);
                assertEquals(WasiErrno.ESUCCESS.value(), result);
                assertEquals(
                        1000, getLastModifiedTime(root.resolve("link"), NOFOLLOW_LINKS).toMillis());
                assertEquals(fileTime, getLastModifiedTime(root.resolve("file")).toMillis());

                result =
                        wasi.pathFilestatSetTimes(
                                3,
                                WasiLookupFlags.SYMLINK_FOLLOW,
                                "link",
                                0,
                                SECONDS.toNanos(2),
                                WasiFstFlags.MTIM);
                assertEquals(WasiErrno.ESUCCESS.value(), result);
                assertEquals(2000, getLastModifiedTime(root.resolve("file")).toMillis());
            }
        }
    }

    @Test
    public void filestatWithoutUnixAttributeView() throws IOException {
        // Windows' default file system, and ZeroFs's stock configurations, have no "unix" view
        var configurations =
                new Configuration[] {
                    Configuration.unix().toBuilder().setAttributeViews("basic").build(),
                    Configuration.windows()
                };
        for (Configuration configuration : configurations) {
            try (var fs = ZeroFs.newFileSystem(configuration)) {
                assertFileStat(fs.getPath("test"));
            }
        }
    }

    @Test
    public void filestatOnHostFileSystem(@TempDir Path tempDir) throws IOException {
        assertFileStat(tempDir.resolve("test"));
    }

    private static void assertFileStat(Path root) throws IOException {
        createDirectory(root);
        writeString(root.resolve("file"), "hello");
        createDirectory(root.resolve("dir"));
        try (var wasi = wasiWithDirectory("test", root)) {
            var memory = new ByteBufferMemory(new MemoryLimits(1));
            int buf = 0;

            assertEquals(
                    WasiErrno.ESUCCESS.value(), wasi.pathFilestatGet(memory, 3, 0, "file", buf));
            assertEquals(WasiFileType.REGULAR_FILE.value(), memory.read(buf + 16));
            assertEquals(5, memory.readLong(buf + 32));
            long mtime = getLastModifiedTime(root.resolve("file")).to(NANOSECONDS);
            assertEquals(mtime, memory.readLong(buf + 48));
            long fileIno = memory.readLong(buf + 8);
            assertNotEquals(0, fileIno);

            assertEquals(
                    WasiErrno.ESUCCESS.value(), wasi.pathFilestatGet(memory, 3, 0, "dir", buf));
            long dirIno = memory.readLong(buf + 8);
            assertNotEquals(0, dirIno);
            assertNotEquals(fileIno, dirIno);

            int fdPtr = 4096;
            assertEquals(
                    WasiErrno.ESUCCESS.value(),
                    wasi.pathOpen(memory, 3, 0, "file", 0, 0, 0, 0, fdPtr));
            int fd = memory.readInt(fdPtr);
            assertEquals(WasiErrno.ESUCCESS.value(), wasi.fdFilestatGet(memory, fd, buf));
            assertEquals(WasiFileType.REGULAR_FILE.value(), memory.read(buf + 16));
            assertEquals(5, memory.readLong(buf + 32));
            assertEquals(fileIno, memory.readLong(buf + 8));

            int bufUsedPtr = 2048;
            assertEquals(
                    WasiErrno.ESUCCESS.value(),
                    wasi.fdReaddir(memory, 3, buf, 1024, 0, bufUsedPtr));
            Map<String, Integer> types = new HashMap<>();
            Map<String, Long> inodes = new HashMap<>();
            int used = memory.readInt(bufUsedPtr);
            for (int pos = buf; pos < buf + used; ) {
                int nameLen = memory.readInt(pos + 16);
                String name = memory.readString(pos + 24, nameLen);
                types.put(name, (int) memory.read(pos + 20));
                inodes.put(name, memory.readLong(pos + 8));
                pos += 24 + nameLen;
            }
            assertEquals(WasiFileType.REGULAR_FILE.value(), types.get("file"));
            assertEquals(WasiFileType.DIRECTORY.value(), types.get("dir"));
            assertEquals(fileIno, inodes.get("file"));
            assertEquals(dirIno, inodes.get("dir"));
        }
    }

    @Test
    public void createFileInSymlinkedDirectory(@TempDir Path tempDir) throws IOException {
        Path real = createDirectory(tempDir.resolve("real"));
        Path link = createSymbolicLink(tempDir.resolve("link"), real);
        try (var wasi = wasiWithDirectory("dir", link)) {
            var memory = new ByteBufferMemory(new MemoryLimits(1));
            assertEquals(
                    WasiErrno.ESUCCESS.value(),
                    wasi.pathOpen(
                            memory,
                            3,
                            WasiLookupFlags.SYMLINK_FOLLOW,
                            "new.txt",
                            WasiOpenFlags.CREAT,
                            WasiRights.FD_WRITE,
                            0,
                            0,
                            0));
            assertTrue(exists(real.resolve("new.txt")));
        }
    }

    @Test
    public void symlinkEscapeShouldReturnEacces() throws IOException {
        Path tmpDir = java.nio.file.Files.createTempDirectory("wasi-symlink-test");
        try {
            Path sandbox = tmpDir.resolve("sandbox");
            createDirectory(sandbox);
            Path outside = tmpDir.resolve("outside");
            createDirectory(outside);
            writeString(outside.resolve("secret.txt"), "secret");
            createSymbolicLink(sandbox.resolve("escape"), outside.resolve("secret.txt"));

            try (var wasi = wasiWithDirectory(sandbox.toString(), sandbox)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));
                int buf = 0;
                int result =
                        wasi.pathFilestatGet(
                                memory, 3, WasiLookupFlags.SYMLINK_FOLLOW, "escape", buf);
                assertEquals(WasiErrno.EACCES.value(), result);
            }
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    @Test
    public void pathRenameOnHostFileSystem() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("wasi-rename-test");
        try {
            writeString(dir.resolve("old.txt"), "old");
            writeString(dir.resolve("other.txt"), "other");
            try (var wasi = wasiWithDirectory(dir.toString(), dir)) {
                assertEquals(
                        WasiErrno.ESUCCESS.value(), wasi.pathRename(3, "old.txt", 3, "new.txt"));
                assertFalse(exists(dir.resolve("old.txt")));
                assertEquals("old", readString(dir.resolve("new.txt")));

                assertEquals(
                        WasiErrno.ESUCCESS.value(), wasi.pathRename(3, "other.txt", 3, "new.txt"));
                assertFalse(exists(dir.resolve("other.txt")));
                assertEquals("other", readString(dir.resolve("new.txt")));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void pathRenameAcrossFileSystemsReturnsExdev() throws IOException {
        Path hostDir = java.nio.file.Files.createTempDirectory("wasi-rename-test");
        try (var fs = newZeroFs()) {
            Path zeroDir = fs.getPath("zero");
            createDirectory(zeroDir);
            writeString(zeroDir.resolve("file.txt"), "hello");
            var options =
                    WasiOptions.builder()
                            .withDirectory("zero", zeroDir)
                            .withDirectory("host", hostDir)
                            .build();
            try (var wasi = WasiPreview1.builder().withOptions(options).build()) {
                assertEquals(
                        WasiErrno.EXDEV.value(), wasi.pathRename(3, "file.txt", 4, "file.txt"));
                assertTrue(exists(zeroDir.resolve("file.txt")));
                assertFalse(exists(hostDir.resolve("file.txt")));
            }
        } finally {
            deleteRecursively(hostDir);
        }
    }

    @Test
    public void windowsPathsStayInTheDirectory() throws IOException {
        try (var fs = ZeroFs.newFileSystem(Configuration.windows())) {
            Path dir = java.nio.file.Files.createDirectories(fs.getPath("C:\\work\\dir"));
            writeString(dir.resolve("file.txt"), "hello");
            writeString(fs.getPath("C:\\work\\outside.txt"), "outside");
            try (var wasi = wasiWithDirectory("dir", dir)) {
                var memory = new ByteBufferMemory(new MemoryLimits(1));
                for (String path :
                        new String[] {
                            "../outside.txt",
                            "..\\outside.txt",
                            "sub\\..\\..\\outside.txt",
                            "C:\\work\\outside.txt"
                        }) {
                    assertEquals(
                            WasiErrno.EPERM.value(),
                            wasi.pathOpen(memory, 3, 0, path, 0, 0, 0, 0, 0),
                            path);
                }
                assertEquals(
                        WasiErrno.ESUCCESS.value(),
                        wasi.pathOpen(memory, 3, 0, "sub\\..\\file.txt", 0, 0, 0, 0, 0));
                assertEquals(WasiErrno.EACCES.value(), wasi.pathSymlink("..\\outside.txt", 3, "l"));
            }
        }
    }

    @Test
    public void emptyPathReturnsEnoent() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("wasi-empty-path-test");
        try (var wasi = wasiWithDirectory(dir.toString(), dir)) {
            var memory = new ByteBufferMemory(new MemoryLimits(1));
            int enoent = WasiErrno.ENOENT.value();
            assertEquals(enoent, wasi.pathRemoveDirectory(3, ""));
            assertTrue(exists(dir));

            writeString(dir.resolve("file.txt"), "hello");
            assertEquals(enoent, wasi.pathCreateDirectory(3, ""));
            assertEquals(enoent, wasi.pathFilestatGet(memory, 3, 0, "", 0));
            assertEquals(enoent, wasi.pathFilestatSetTimes(3, 0, "", 0, 0, WasiFstFlags.MTIM_NOW));
            assertEquals(enoent, wasi.pathLink(3, 0, "", 3, "link.txt"));
            assertEquals(enoent, wasi.pathLink(3, 0, "file.txt", 3, ""));
            assertEquals(enoent, wasi.pathReadlink(memory, 3, "", 0, 64, 128));
            assertEquals(enoent, wasi.pathRename(3, "", 3, "moved"));
            assertEquals(enoent, wasi.pathRename(3, "file.txt", 3, ""));
            assertEquals(enoent, wasi.pathSymlink("", 3, "link.txt"));
            assertEquals(enoent, wasi.pathSymlink("file.txt", 3, ""));
            assertEquals(enoent, wasi.pathUnlinkFile(3, ""));
            assertFalse(exists(dir.resolve("link.txt"), NOFOLLOW_LINKS));
            assertEquals("hello", readString(dir.resolve("file.txt")));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void pathOperationsOnTheDirectoryItself() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("wasi-self-path-test");
        try {
            Path a = createDirectory(dir.resolve("a"));
            Path b = createDirectory(dir.resolve("b"));
            createDirectory(b.resolve("sub"));
            var options = WasiOptions.builder().withDirectory("a", a).withDirectory("b", b).build();
            try (var wasi = WasiPreview1.builder().withOptions(options).build()) {
                int einval = WasiErrno.EINVAL.value();
                assertEquals(einval, wasi.pathRemoveDirectory(3, "."));
                assertEquals(einval, wasi.pathRemoveDirectory(4, "sub/.."));
                assertTrue(exists(a));

                int ebusy = WasiErrno.EBUSY.value();
                assertEquals(ebusy, wasi.pathRename(3, ".", 4, "moved"));
                assertEquals(ebusy, wasi.pathRename(4, "sub", 3, "."));
                assertTrue(exists(a));
                assertTrue(exists(b.resolve("sub")));
                assertFalse(exists(b.resolve("moved")));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var paths = java.nio.file.Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    java.nio.file.Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // best-effort cleanup
                                }
                            });
        }
    }

    private static FileSystem newZeroFs() {
        return ZeroFs.newFileSystem(
                Configuration.unix().toBuilder().setAttributeViews("unix").build());
    }

    private static WasiPreview1 wasiWithDirectory(String guest, Path host) {
        var options = WasiOptions.builder().withDirectory(guest, host).build();
        return WasiPreview1.builder().withOptions(options).build();
    }
}
