package run.endive.redline.experimental.runner.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import run.endive.redline.experimental.api.internal.RedlineTarget;

/**
 * Helpers for native memory management via Panama FFM.
 * Uses mmap/mprotect/munmap on POSIX, VirtualAlloc/VirtualProtect/VirtualFree on Windows.
 */
final class PanamaExecutor {

    private PanamaExecutor() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final boolean IS_WINDOWS;

    /** Raw address of libc memmove, for use as a native function pointer. */
    static final long MEMMOVE_ADDR;

    /** Raw address of libc memset, for use as a native function pointer. */
    static final long MEMSET_ADDR;

    // --- POSIX handles (null on Windows) ---
    private static final java.lang.invoke.MethodHandle MMAP;
    private static final java.lang.invoke.MethodHandle MPROTECT;
    private static final java.lang.invoke.MethodHandle MUNMAP;

    // POSIX constants
    private static final int PROT_READ = 0x1;
    private static final int PROT_WRITE = 0x2;
    private static final int PROT_EXEC = 0x4;
    private static final int MAP_PRIVATE = 0x02;
    private static final int MAP_ANONYMOUS;

    // --- Windows handles (null on POSIX) ---
    private static final java.lang.invoke.MethodHandle VIRTUAL_ALLOC;
    private static final java.lang.invoke.MethodHandle VIRTUAL_PROTECT;
    private static final java.lang.invoke.MethodHandle VIRTUAL_FREE;

    // Windows constants
    private static final int MEM_COMMIT = 0x00001000;
    private static final int MEM_RESERVE = 0x00002000;
    private static final int MEM_RELEASE = 0x00008000;
    private static final int PAGE_NOACCESS = 0x01;
    private static final int PAGE_READWRITE = 0x04;
    private static final int PAGE_EXECUTE_READ = 0x20;

    static {
        var host = RedlineTarget.detectHost().orElseThrow();
        IS_WINDOWS = host == RedlineTarget.WINDOWS_X86_64 || host == RedlineTarget.WINDOWS_AARCH64;
        boolean isMac = host == RedlineTarget.MACOS_X86_64 || host == RedlineTarget.MACOS_AARCH64;
        MAP_ANONYMOUS = isMac ? 0x1000 : 0x20;

        var lookup = LINKER.defaultLookup();
        MEMMOVE_ADDR = lookup.find("memmove").orElseThrow().address();
        MEMSET_ADDR = lookup.find("memset").orElseThrow().address();

        try {
            if (IS_WINDOWS) {
                var kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                VIRTUAL_ALLOC =
                        LINKER.downcallHandle(
                                kernel32.find("VirtualAlloc").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT));
                VIRTUAL_PROTECT =
                        LINKER.downcallHandle(
                                kernel32.find("VirtualProtect").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS));
                VIRTUAL_FREE =
                        LINKER.downcallHandle(
                                kernel32.find("VirtualFree").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_INT));
                MMAP = null;
                MPROTECT = null;
                MUNMAP = null;
            } else {
                MMAP =
                        LINKER.downcallHandle(
                                lookup.find("mmap").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_LONG));
                MPROTECT =
                        LINKER.downcallHandle(
                                lookup.find("mprotect").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.JAVA_INT));
                MUNMAP =
                        LINKER.downcallHandle(
                                lookup.find("munmap").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG));
                VIRTUAL_ALLOC = null;
                VIRTUAL_PROTECT = null;
                VIRTUAL_FREE = null;
            }
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Allocate a writable memory region. Must call mprotectExec before executing. */
    static MemorySegment mmapCode(long size) throws Throwable {
        if (IS_WINDOWS) {
            MemorySegment addr =
                    (MemorySegment)
                            VIRTUAL_ALLOC.invokeExact(
                                    MemorySegment.NULL,
                                    size,
                                    MEM_RESERVE | MEM_COMMIT,
                                    PAGE_READWRITE);
            if (addr.address() == 0) {
                throw new RuntimeException("VirtualAlloc failed");
            }
            return addr.reinterpret(size);
        }
        MemorySegment addr =
                (MemorySegment)
                        MMAP.invokeExact(
                                MemorySegment.NULL,
                                size,
                                PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS,
                                -1,
                                0L);
        return addr.reinterpret(size);
    }

    /** Make a previously mmapped region executable (and remove write). */
    static void mprotectExec(MemorySegment addr, long size) throws Throwable {
        if (IS_WINDOWS) {
            try (var arena = Arena.ofConfined()) {
                var oldProtect = arena.allocate(ValueLayout.JAVA_INT);
                int result =
                        (int)
                                VIRTUAL_PROTECT.invokeExact(
                                        addr, size, PAGE_EXECUTE_READ, oldProtect);
                if (result == 0) {
                    throw new RuntimeException("VirtualProtect failed");
                }
            }
            return;
        }
        int result = (int) MPROTECT.invokeExact(addr, size, PROT_READ | PROT_EXEC);
        if (result != 0) {
            throw new RuntimeException("mprotect failed: " + result);
        }
    }

    /** Reserve address space with no access (PROT_NONE). */
    static MemorySegment mmapNoAccess(long size) throws Throwable {
        if (size == 0) {
            return MemorySegment.NULL;
        }
        if (IS_WINDOWS) {
            MemorySegment addr =
                    (MemorySegment)
                            VIRTUAL_ALLOC.invokeExact(
                                    MemorySegment.NULL, size, MEM_RESERVE, PAGE_NOACCESS);
            if (addr.address() == 0) {
                throw new RuntimeException("VirtualAlloc (reserve) failed");
            }
            return addr.reinterpret(size);
        }
        MemorySegment addr =
                (MemorySegment)
                        MMAP.invokeExact(
                                MemorySegment.NULL,
                                size,
                                0, // PROT_NONE
                                MAP_PRIVATE | MAP_ANONYMOUS,
                                -1,
                                0L);
        return addr.reinterpret(size);
    }

    /** Make a range readable and writable (commits reserved pages on Windows). */
    static void mprotectReadWrite(MemorySegment addr, long size) throws Throwable {
        if (size == 0) {
            return;
        }
        if (IS_WINDOWS) {
            // Must use VirtualAlloc with MEM_COMMIT to commit reserved pages
            MemorySegment result =
                    (MemorySegment)
                            VIRTUAL_ALLOC.invokeExact(addr, size, MEM_COMMIT, PAGE_READWRITE);
            if (result.address() == 0) {
                throw new RuntimeException("VirtualAlloc (commit) failed");
            }
            return;
        }
        int result = (int) MPROTECT.invokeExact(addr, size, PROT_READ | PROT_WRITE);
        if (result != 0) {
            throw new RuntimeException("mprotect failed: " + result);
        }
    }

    /** Unmap a memory region. */
    static void munmap(MemorySegment addr, long size) throws Throwable {
        if (IS_WINDOWS) {
            // VirtualFree with MEM_RELEASE requires size=0 (frees entire allocation)
            int result = (int) VIRTUAL_FREE.invokeExact(addr, 0L, MEM_RELEASE);
            if (result == 0) {
                throw new RuntimeException("VirtualFree failed");
            }
            return;
        }
        int result = (int) MUNMAP.invokeExact(addr, size);
        if (result != 0) {
            throw new RuntimeException("munmap failed: " + result);
        }
    }
}
