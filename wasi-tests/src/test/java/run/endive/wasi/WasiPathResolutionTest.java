package run.endive.wasi;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createSymbolicLink;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.getLastModifiedTime;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.writeString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import run.endive.runtime.ByteBufferMemory;
import run.endive.wasm.types.MemoryLimits;

@Timeout(10)
public class WasiPathResolutionTest {
    private static final int EPERM = WasiErrno.EPERM.value();
    private static final int ENOENT = WasiErrno.ENOENT.value();
    private static final int SUCCESS = WasiErrno.ESUCCESS.value();
    private static final int FOLLOW = WasiLookupFlags.SYMLINK_FOLLOW;

    private final ByteBufferMemory memory = new ByteBufferMemory(new MemoryLimits(1));

    interface Body {
        void run(Path sandbox, Path outside) throws IOException;
    }

    @Test
    public void symlinksCannotLeadOutside(@TempDir Path tempDir) throws IOException {
        onBothFileSystems(
                tempDir,
                (sandbox, outside) -> {
                    writeString(sandbox.resolve("file.txt"), "inside");
                    createDirectory(outside.resolve("empty"));
                    createSymbolicLink(sandbox.resolve("abs"), outside.toAbsolutePath());
                    createSymbolicLink(
                            sandbox.resolve("rel"), sandbox.getFileSystem().getPath("../outside"));
                    createSymbolicLink(
                            sandbox.resolve("secret"),
                            outside.resolve("secret.txt").toAbsolutePath());
                    FileTime secretTime = getLastModifiedTime(outside.resolve("secret.txt"));

                    try (var wasi = wasi(sandbox)) {
                        for (String link : new String[] {"abs", "rel"}) {
                            String secret = link + "/secret.txt";
                            assertEquals(EPERM, open(wasi, 3, 0, secret), secret);
                            assertEquals(EPERM, open(wasi, 3, FOLLOW, link), link);
                            assertEquals(EPERM, wasi.pathCreateDirectory(3, link + "/d"));
                            assertEquals(EPERM, wasi.pathUnlinkFile(3, secret));
                            assertEquals(EPERM, wasi.pathRemoveDirectory(3, link + "/empty"));
                            assertEquals(EPERM, wasi.pathRename(3, secret, 3, "moved"));
                            assertEquals(EPERM, wasi.pathRename(3, "file.txt", 3, link + "/x"));
                            assertEquals(EPERM, wasi.pathLink(3, 0, secret, 3, "hard"));
                            assertEquals(EPERM, wasi.pathLink(3, 0, "file.txt", 3, link + "/x"));
                            assertEquals(EPERM, wasi.pathSymlink("x", 3, link + "/x"));
                            assertEquals(EPERM, wasi.pathFilestatGet(memory, 3, 0, secret, 0));
                            assertEquals(EPERM, wasi.pathFilestatGet(memory, 3, FOLLOW, link, 0));
                            assertEquals(
                                    EPERM,
                                    wasi.pathFilestatSetTimes(
                                            3, FOLLOW, link, 0, 0, WasiFstFlags.MTIM));
                            assertEquals(EPERM, wasi.pathReadlink(memory, 3, secret, 0, 64, 128));
                        }
                        assertEquals(EPERM, open(wasi, 3, 0, "../outside/secret.txt"));
                        assertEquals(EPERM, wasi.pathLink(3, 0, "secret", 3, "hard"));
                    }

                    assertEquals("secret", readString(outside.resolve("secret.txt")));
                    assertEquals(secretTime, getLastModifiedTime(outside.resolve("secret.txt")));
                    try (Stream<Path> entries = java.nio.file.Files.list(outside)) {
                        assertEquals(2, entries.count());
                    }
                });
    }

    @Test
    public void descriptorsDoNotFollowReplacedNames(@TempDir Path tempDir) throws IOException {
        onBothFileSystems(
                tempDir,
                (sandbox, outside) -> {
                    createDirectory(sandbox.resolve("d"));
                    writeString(sandbox.resolve("f.txt"), "file");
                    FileTime secretTime = getLastModifiedTime(outside.resolve("secret.txt"));

                    try (var wasi = wasi(sandbox)) {
                        int dirFd = openFd(wasi, "d", WasiOpenFlags.DIRECTORY);
                        int fileFd = openFd(wasi, "f.txt", 0);
                        assertEquals(SUCCESS, wasi.pathRename(3, "d", 3, "e"));
                        assertEquals(SUCCESS, wasi.pathRename(3, "f.txt", 3, "g"));
                        createSymbolicLink(
                                sandbox.resolve("d"),
                                sandbox.getFileSystem().getPath("../outside"));
                        createSymbolicLink(
                                sandbox.resolve("f.txt"),
                                sandbox.getFileSystem().getPath("../outside/secret.txt"));

                        assertEquals(ENOENT, open(wasi, dirFd, 0, "secret.txt"));
                        assertEquals(ENOENT, wasi.pathCreateDirectory(dirFd, "created"));
                        assertEquals(ENOENT, wasi.fdReaddir(memory, dirFd, 0, 64, 0, 128));
                        wasi.fdFilestatSetTimes(fileFd, 0, 0, WasiFstFlags.MTIM);
                    }

                    assertFalse(exists(outside.resolve("created")));
                    assertEquals(secretTime, getLastModifiedTime(outside.resolve("secret.txt")));
                });
    }

    @Test
    public void preopenedSymlinkIsADirectory(@TempDir Path tempDir) throws IOException {
        onBothFileSystems(
                tempDir,
                (sandbox, outside) -> {
                    Path link =
                            createSymbolicLink(
                                    sandbox.resolveSibling("link"), sandbox.toAbsolutePath());
                    try (var wasi = wasi(link)) {
                        assertEquals(SUCCESS, wasi.fdFilestatGet(memory, 3, 0));
                        assertEquals(WasiFileType.DIRECTORY.value(), memory.read(16));
                        assertEquals(SUCCESS, wasi.pathFilestatGet(memory, 3, 0, ".", 0));
                        assertEquals(WasiFileType.DIRECTORY.value(), memory.read(16));
                        openFd(wasi, ".", WasiOpenFlags.DIRECTORY);
                    }
                });
    }

    private static void onBothFileSystems(Path tempDir, Body body) throws IOException {
        run(tempDir, body);
        try (FileSystem fs =
                ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder().setAttributeViews("unix").build())) {
            run(createDirectory(fs.getPath("/base")), body);
        }
    }

    private static void run(Path base, Body body) throws IOException {
        Path sandbox = createDirectory(base.resolve("sandbox"));
        Path outside = createDirectory(base.resolve("outside"));
        writeString(outside.resolve("secret.txt"), "secret");
        body.run(sandbox, outside);
    }

    private int open(WasiPreview1 wasi, int dirFd, int lookupFlags, String path) {
        return wasi.pathOpen(memory, dirFd, lookupFlags, path, 0, WasiRights.FD_READ, 0, 0, 0);
    }

    private int openFd(WasiPreview1 wasi, String path, int openFlags) {
        assertEquals(
                SUCCESS, wasi.pathOpen(memory, 3, 0, path, openFlags, WasiRights.FD_READ, 0, 0, 0));
        return memory.readInt(0);
    }

    private static WasiPreview1 wasi(Path directory) {
        var options = WasiOptions.builder().withDirectory("/sandbox", directory).build();
        return WasiPreview1.builder().withOptions(options).build();
    }
}
