package run.endive.wasi;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createSymbolicLink;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.getLastModifiedTime;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.writeString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final int EEXIST = WasiErrno.EEXIST.value();
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
                            assertEquals(EPERM, wasi.pathLink(3, 0, link, 3, "hard"));
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

                        assertEquals(SUCCESS, wasi.pathSymlink("../outside/missing", 3, "gone"));
                        assertEquals(EEXIST, wasi.pathSymlink("x", 3, "gone"));
                        assertEquals(EEXIST, wasi.pathLink(3, 0, "file.txt", 3, "gone"));
                    }

                    assertEquals("secret", readString(outside.resolve("secret.txt")));
                    assertEquals(secretTime, getLastModifiedTime(outside.resolve("secret.txt")));
                    try (Stream<Path> entries = java.nio.file.Files.list(outside)) {
                        assertEquals(2, entries.count());
                    }
                });
    }

    @Test
    public void absoluteLinksAreFollowedInside(@TempDir Path tempDir) throws IOException {
        onBothFileSystems(
                tempDir,
                (sandbox, outside) -> {
                    Path root = sandbox.toAbsolutePath();
                    createDirectory(sandbox.resolve("d"));
                    createDirectory(sandbox.resolve("sibling"));
                    writeString(sandbox.resolve("d/file.txt"), "inside");
                    writeString(sandbox.resolve("sibling/file.txt"), "sibling");
                    createSymbolicLink(sandbox.resolve("dir"), root.resolve("d"));
                    createSymbolicLink(sandbox.resolve("file"), root.resolve("d/file.txt"));
                    createSymbolicLink(sandbox.resolve("chain"), root.resolve("dir"));
                    createSymbolicLink(sandbox.resolve("itself"), root);
                    createSymbolicLink(sandbox.resolve("around"), root.resolve("d/../dir"));
                    createSymbolicLink(sandbox.resolve("out"), outside.toAbsolutePath());
                    createSymbolicLink(sandbox.resolve("up"), root.resolve("../outside"));
                    createSymbolicLink(sandbox.resolve("d/sibling"), root.resolve("sibling"));
                    createSymbolicLink(sandbox.resolve("d/same"), root.resolve("d/file.txt"));

                    try (var wasi = wasi(sandbox)) {
                        assertEquals(SUCCESS, open(wasi, 3, 0, "dir/file.txt"));
                        assertEquals(SUCCESS, open(wasi, 3, FOLLOW, "file"));
                        assertEquals(SUCCESS, open(wasi, 3, 0, "chain/file.txt"));
                        assertEquals(SUCCESS, open(wasi, 3, 0, "itself/d/file.txt"));
                        assertEquals(SUCCESS, open(wasi, 3, 0, "around/file.txt"));
                        assertEquals(SUCCESS, open(wasi, 3, 0, "d/sibling/file.txt"));
                        assertEquals(SUCCESS, wasi.pathFilestatGet(memory, 3, FOLLOW, "file", 0));
                        assertEquals(WasiFileType.REGULAR_FILE.value(), memory.read(16));
                        assertEquals(SUCCESS, wasi.pathCreateDirectory(3, "dir/created"));

                        // ".." after the walk started again cannot leave the directory either
                        assertEquals(EPERM, open(wasi, 3, 0, "itself/../outside/secret.txt"));
                        assertEquals(EPERM, open(wasi, 3, 0, "out/secret.txt"));
                        assertEquals(EPERM, open(wasi, 3, 0, "up/secret.txt"));
                        assertEquals(EPERM, wasi.pathCreateDirectory(3, "up/created"));

                        // a directory's descriptor is confined to that directory
                        int dirFd = openFd(wasi, "d", WasiOpenFlags.DIRECTORY);
                        assertEquals(SUCCESS, open(wasi, dirFd, FOLLOW, "same"));
                        assertEquals(EPERM, open(wasi, dirFd, 0, "sibling/file.txt"));
                        assertEquals(EPERM, open(wasi, dirFd, FOLLOW, "sibling"));

                        // a guest cannot create one: its paths are not host paths
                        assertEquals(
                                EPERM, wasi.pathSymlink(root.resolve("d").toString(), 3, "new"));
                    }

                    assertTrue(exists(sandbox.resolve("d/created")));
                    assertFalse(exists(outside.resolve("created")));
                });
    }

    @Test
    public void absoluteLinksMayNameTheRealDirectory(@TempDir Path tempDir) throws IOException {
        onBothFileSystems(
                tempDir,
                (sandbox, outside) -> {
                    // the directory is mapped through a link in its parents: its configured
                    // path and its real path differ
                    Path real = sandbox.toRealPath();
                    Path via =
                            createSymbolicLink(
                                    sandbox.resolveSibling("via"),
                                    sandbox.toAbsolutePath().getParent());
                    Path configured = via.resolve("sandbox").toAbsolutePath();
                    writeString(sandbox.resolve("file.txt"), "inside");
                    createSymbolicLink(sandbox.resolve("real"), real.resolve("file.txt"));
                    createSymbolicLink(
                            sandbox.resolve("configured"), configured.resolve("file.txt"));
                    createSymbolicLink(
                            sandbox.resolve("out"), via.toAbsolutePath().resolve("outside"));

                    try (var wasi = wasi(via.resolve("sandbox"))) {
                        assertEquals(SUCCESS, open(wasi, 3, FOLLOW, "real"));
                        assertEquals(SUCCESS, open(wasi, 3, FOLLOW, "configured"));
                        assertEquals(EPERM, open(wasi, 3, 0, "out/secret.txt"));
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

    @Test
    public void invalidUtf8PathsAreRejected(@TempDir Path tempDir) throws IOException {
        onBothFileSystems(
                tempDir,
                (sandbox, outside) -> {
                    int eilseq = WasiErrno.EILSEQ.value();
                    try (var wasi = wasi(sandbox)) {
                        assertEquals(eilseq, wasi.pathCreateDirectory(3, "\uFFFD"));
                        assertEquals(eilseq, wasi.pathSymlink("\uFFFD", 3, "link"));
                        assertEquals(eilseq, wasi.pathReadlink(memory, 3, "\uFFFD", 0, 0, 128));
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
