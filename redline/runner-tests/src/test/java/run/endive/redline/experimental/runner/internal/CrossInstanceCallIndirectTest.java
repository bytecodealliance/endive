package run.endive.redline.experimental.runner.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.api.NativeCode;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.testing.NativeInstanceBuilder;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/**
 * A function placed in a table runs in the instance that defined it, whichever
 * instance calls it through the table. The spec suite can't catch a runner that
 * gets this wrong, because its shared-table functions only return constants and
 * behave the same in any instance. The functions here read their own global and
 * memory, call their own import, and trap.
 *
 * <p>This is how wit-component lays out every component: a shim module's
 * functions are call_indirects through a table that a fixup module fills with
 * another instance's functions.
 */
public class CrossInstanceCallIndirectTest {

    /**
     * Owns the functions. Its global and memory hold 42 and 1000, which the
     * caller's do not.
     */
    private static final String OWNER =
            """
            (module
              (import "host" "seven" (func $host (result i32)))
              (memory 1)
              (global $g (mut i32) (i32.const 42))
              (table (export "tab") 6 funcref)
              (data (i32.const 16) "\\e8\\03\\00\\00")
              (func $readGlobal (result i32) global.get $g)
              (func $readMemory (result i32) (i32.load (i32.const 16)))
              (func $trap (result i32) unreachable)
              (func $pair (result i32 i32) global.get $g (i32.load (i32.const 16)))
              (func $bumpGlobal (result i32)
                (global.set $g (i32.add (global.get $g) (i32.const 1)))
                global.get $g)
              (elem (i32.const 0) $readGlobal $readMemory $host $trap $pair $bumpGlobal))
            """;

    /**
     * Calls through the owner's table. Its own global and memory differ from
     * the owner's, and its types are declared in a different order, so type
     * indices differ between the two.
     */
    private static final String CALLER =
            """
            (module
              (type $byIndex (func (param i32) (result i32)))
              (type $i64 (func (result i64)))
              (type $pair (func (result i32 i32)))
              (type $i32 (func (result i32)))
              (import "owner" "tab" (table 6 funcref))
              (memory 1)
              (global $g (mut i32) (i32.const -1))
              (func (export "call") (type $byIndex)
                (call_indirect (type $i32) (local.get 0)))
              (func (export "tailCall") (type $byIndex)
                (return_call_indirect (type $i32) (local.get 0)))
              (func (export "callPair") (result i32)
                (call_indirect (type $pair) (i32.const 4))
                i32.sub)
              (func (export "wrongType") (result i64)
                (call_indirect (type $i64) (i32.const 0))))
            """;

    /** Defines an empty table and calls through it. */
    private static final String HOLDER =
            """
            (module
              (type $i32 (func (result i32)))
              (table (export "tab") 2 4 funcref)
              (func (export "call") (param i32) (result i32)
                (call_indirect (type $i32) (local.get 0))))
            """;

    /** Puts its function, which returns 42, in the holder's table. */
    private static final String WRITER =
            """
            (module
              (import "holder" "tab" (table 2 funcref))
              (global $g i32 (i32.const 42))
              (func $answer (result i32) global.get $g)
              (elem (i32.const 0) $answer))
            """;

    /**
     * Calls through the holder's table. Its own function has the same index as
     * the writer's but returns -1.
     */
    private static final String READER =
            """
            (module
              (type $i32 (func (result i32)))
              (import "holder" "tab" (table 2 funcref))
              (global $g i32 (i32.const -1))
              (func $own (result i32) global.get $g)
              (func (export "call") (param i32) (result i32)
                (call_indirect (type $i32) (local.get 0))))
            """;

    @Test
    public void calleeReadsItsOwnGlobal() {
        try (var linked = linkNative()) {
            assertEquals(
                    42, call(linked.caller, "call", 0), "the owner's global, not the caller's");
        }
    }

    @Test
    public void calleeReadsItsOwnMemory() {
        try (var linked = linkNative()) {
            assertEquals(
                    1000, call(linked.caller, "call", 1), "the owner's memory, not the caller's");
        }
    }

    @Test
    public void calleeWritesItsOwnGlobal() {
        try (var linked = linkNative()) {
            assertEquals(43, call(linked.caller, "call", 5));
            assertEquals(44, call(linked.caller, "call", 5));
            assertEquals(
                    44, (int) linked.owner.global(0).getValue(), "the write landed in the owner");
        }
    }

    @Test
    public void calleeCallsItsOwnImport() {
        try (var linked = linkNative()) {
            assertEquals(7, call(linked.caller, "call", 2));
        }
    }

    @Test
    public void tailCallRunsInTheOwner() {
        try (var linked = linkNative()) {
            assertEquals(42, call(linked.caller, "tailCall", 0));
            assertEquals(1000, call(linked.caller, "tailCall", 1));
        }
    }

    @Test
    public void multiValueResultsReachTheCaller() {
        try (var linked = linkNative()) {
            assertEquals(42 - 1000, (int) linked.caller.export("callPair").apply()[0]);
        }
    }

    /** A host function may return no results, which the import path already tolerates. */
    @Test
    public void multiValueHostFunctionReturningNull() {
        var none =
                new HostFunction(
                        "host",
                        "none",
                        FunctionType.of(List.of(), List.of(ValType.I32, ValType.I32)),
                        (inst, args) -> null);
        var store = new Store().addFunction(none);
        try (var owner =
                nativeInstance(
                        """
                        (module
                          (import "host" "none" (func $none (result i32 i32)))
                          (table (export "tab") 1 funcref)
                          (elem (i32.const 0) $none))
                        """,
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            """
                            (module
                              (type $pair (func (result i32 i32)))
                              (import "owner" "tab" (table 1 funcref))
                              (func (export "call")
                                (call_indirect (type $pair) (i32.const 0))
                                drop
                                drop))
                            """,
                            store)) {
                assertDoesNotThrow(() -> caller.export("call").apply());
            }
        }
    }

    @Test
    public void calleeTrapStopsTheCaller() {
        try (var linked = linkNative()) {
            var e = assertThrows(WasmEngineException.class, () -> call(linked.caller, "call", 3));
            assertTrue(e.getMessage().contains("unreachable"), e.getMessage());
        }
    }

    /** Compiled code caches the page count, so the caller must see a growth in the callee. */
    @Test
    public void callerSeesMemoryTheCalleeGrew() {
        var store = new Store();
        try (var owner =
                nativeInstance(
                        """
                        (module
                          (memory (export "mem") 1)
                          (table (export "tab") 1 funcref)
                          (func $grow (result i32) (memory.grow (i32.const 1)))
                          (elem (i32.const 0) $grow))
                        """,
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            """
                            (module
                              (type $i32 (func (result i32)))
                              (import "owner" "mem" (memory 1))
                              (import "owner" "tab" (table 1 funcref))
                              (func (export "growThenStore") (result i32)
                                (drop (call_indirect (type $i32) (i32.const 0)))
                                (i32.store (i32.const 65536) (memory.size))
                                (i32.load (i32.const 65536))))
                            """,
                            store)) {
                assertEquals(2, (int) caller.export("growThenStore").apply()[0]);
            }
        }
    }

    /**
     * The callee gets what is left of the caller's stack budget, not a new one,
     * or a chain of instances could outgrow the thread's stack.
     */
    @Test
    public void calleeSharesTheCallersStackBudget() {
        var store = new Store();
        try (var owner =
                nativeInstance(
                        """
                        (module
                          (global (export "depth") (mut i32) (i32.const 0))
                          (table (export "tab") 1 funcref)
                          (func $recurse (result i32)
                            (global.set 0 (i32.add (global.get 0) (i32.const 1)))
                            (call $recurse))
                          (elem (i32.const 0) $recurse))
                        """,
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            """
                            (module
                              (type $i32 (func (result i32)))
                              (import "owner" "tab" (table 1 funcref))
                              (global (export "depth") (mut i32) (i32.const 0))
                              (func $descend (export "descend") (param i32) (result i32)
                                (global.set 0 (i32.add (global.get 0) (i32.const 1)))
                                (if (result i32) (local.get 0)
                                  (then (call $descend (i32.sub (local.get 0) (i32.const 1))))
                                  (else (call_indirect (type $i32) (i32.const 0))))))
                            """,
                            store)) {
                int callerBudget = depthReached(caller, -1, caller);
                int fromTheTop = depthReached(caller, 0, owner);
                int fromHalfway = depthReached(caller, callerBudget / 2, owner);
                assertTrue(
                        fromHalfway < fromTheTop * 3 / 4,
                        fromHalfway + " levels from halfway, " + fromTheTop + " from the top");
            }
        }
    }

    /** A call through a function import reaches the callee via the host, and shares the budget too. */
    @Test
    public void importedFunctionSharesTheCallersStackBudget() {
        var store = new Store();
        try (var owner =
                nativeInstance(
                        """
                        (module
                          (global (export "depth") (mut i32) (i32.const 0))
                          (func $recurse (export "recurse") (result i32)
                            (global.set 0 (i32.add (global.get 0) (i32.const 1)))
                            (call $recurse)))
                        """,
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            """
                            (module
                              (import "owner" "recurse" (func $recurse (result i32)))
                              (global (export "depth") (mut i32) (i32.const 0))
                              (func $descend (export "descend") (param i32) (result i32)
                                (global.set 0 (i32.add (global.get 0) (i32.const 1)))
                                (if (result i32) (local.get 0)
                                  (then
                                    (call $descend (i32.sub (local.get 0) (i32.const 1))))
                                  (else (call $recurse)))))
                            """,
                            store)) {
                int callerBudget = depthReached(caller, -1, caller);
                int fromTheTop = depthReached(caller, 0, owner);
                int fromHalfway = depthReached(caller, callerBudget / 2, owner);
                assertTrue(
                        fromHalfway < fromTheTop * 3 / 4,
                        fromHalfway + " levels from halfway, " + fromTheTop + " from the top");
            }
        }
    }

    @Test
    public void signatureIsCheckedByStructure() {
        try (var linked = linkNative()) {
            var e =
                    assertThrows(
                            WasmEngineException.class,
                            () -> linked.caller.export("wrongType").apply());
            assertTrue(e.getMessage().contains("indirect call type mismatch"), e.getMessage());
        }
    }

    /** As in the interpreter, a function whose declared type is a subtype matches. */
    @Test
    public void signatureAcceptsADeclaredSubtype() {
        var store = new Store();
        try (var owner =
                nativeInstance(
                        """
                        (module
                          (type $super (sub (func (result funcref))))
                          (type $sub (sub $super (func (result (ref func)))))
                          (table (export "tab") 1 funcref)
                          (func $f (type $sub) (ref.func $f))
                          (elem (i32.const 0) $f))
                        """,
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            """
                            (module
                              (type $super (sub (func (result funcref))))
                              (import "owner" "tab" (table 1 funcref))
                              (func (export "call") (result i32)
                                (ref.is_null (call_indirect (type $super) (i32.const 0)))))
                            """,
                            store)) {
                assertEquals(0, (int) caller.export("call").apply()[0]);
            }
        }
    }

    @Test
    public void tableReportsTheOwnerOfEachEntry() {
        try (var linked = linkNative()) {
            assertSame(linked.owner, linked.owner.table(0).instance(0));
        }
    }

    @Test
    public void nullEntryTraps() {
        try (var holder = nativeInstance(HOLDER, new Store())) {
            var e = assertThrows(WasmEngineException.class, () -> call(holder, "call", 0));
            assertTrue(e.getMessage().contains("uninitialized element"), e.getMessage());
        }
    }

    /**
     * The table belongs to a native instance and an interpreted instance fills
     * it with its own functions, so the entries have no native address to call.
     */
    @Test
    public void entriesFromAnInterpretedInstance() {
        var store = new Store();
        try (var tableOwner =
                nativeInstance(
                        """
                        (module
                          (type $i32 (func (result i32)))
                          (table (export "tab") 2 funcref)
                          (global $g i32 (i32.const -1))
                          (func $own (result i32) global.get $g)
                          (elem (i32.const 1) $own)
                          (func (export "call") (param i32) (result i32)
                            (call_indirect (type $i32) (local.get 0))))
                        """,
                        store)) {
            store.register("owner", tableOwner);
            try (var filler =
                    interpretedInstance(
                            """
                            (module
                              (import "owner" "tab" (table 2 funcref))
                              (global $g i32 (i32.const 42))
                              (func $answer (result i32) global.get $g)
                              (elem (i32.const 0) $answer))
                            """,
                            store)) {
                assertEquals(42, call(tableOwner, "call", 0), "the interpreted filler's function");
                assertEquals(-1, call(tableOwner, "call", 1), "the table owner's own function");
                assertSame(filler, tableOwner.table(0).instance(0));
            }
        }
    }

    /** The interpreter learns the native owner of an entry from the table. */
    @Test
    public void interpretedCallerRunsTheNativeOwner() {
        var store = storeWithHost();
        try (var owner = nativeInstance(OWNER, store)) {
            store.register("owner", owner);
            try (var caller = interpretedInstance(CALLER, store)) {
                assertEquals(42, call(caller, "call", 0));
                assertEquals(1000, call(caller, "call", 1));
                assertEquals(7, call(caller, "call", 2));
            }
        }
    }

    /** No one calls the defining instance, so only the table says it owns the entries. */
    @Test
    public void tableInitialiserBelongsToItsDefiningInstance() {
        var store = new Store();
        try (var definer =
                nativeInstance(
                        """
                        (module
                          (global $g i32 (i32.const 42))
                          (func $answer (result i32) global.get $g)
                          (table (export "tab") 2 funcref (ref.func $answer)))
                        """,
                        store)) {
            store.register("holder", definer);
            try (var reader = nativeInstance(READER, store)) {
                assertEquals(42, call(reader, "call", 1), "the definer's function");
                assertSame(definer, definer.table(0).instance(1));
            }
        }
    }

    /** Entries copied out of an imported table still run in their owner. */
    @Test
    public void copiedEntriesKeepTheirNativeOwner() {
        var store = storeWithHost();
        try (var owner = nativeInstance(OWNER, store)) {
            store.register("owner", owner);
            try (var copier =
                    nativeInstance(
                            """
                            (module
                              (type $i32 (func (result i32)))
                              (import "owner" "tab" (table $imported 6 funcref))
                              (table $own 6 funcref)
                              (func (export "copy")
                                (table.copy $own $imported
                                  (i32.const 0) (i32.const 0) (i32.const 6)))
                              (func (export "call") (param i32) (result i32)
                                (call_indirect $own (type $i32) (local.get 0))))
                            """,
                            store)) {
                copier.export("copy").apply();
                assertEquals(42, call(copier, "call", 0));
                assertEquals(1000, call(copier, "call", 1));
                assertSame(owner, copier.table(1).instance(0));
            }
        }
    }

    /** Copies within and across tables keep the owners an interpreted instance wrote. */
    @Test
    public void copiedEntriesKeepTheirInterpretedOwner() {
        var store = new Store();
        try (var tableOwner =
                nativeInstance(
                        """
                        (module
                          (type $i32 (func (result i32)))
                          (table $a (export "a") 4 funcref)
                          (table $b 4 funcref)
                          (func (export "copyAToB")
                            (table.copy $b $a (i32.const 0) (i32.const 0) (i32.const 4)))
                          (func (export "shiftA")
                            (table.copy $a $a (i32.const 1) (i32.const 0) (i32.const 3)))
                          (func (export "callA") (param i32) (result i32)
                            (call_indirect $a (type $i32) (local.get 0)))
                          (func (export "callB") (param i32) (result i32)
                            (call_indirect $b (type $i32) (local.get 0))))
                        """,
                        store)) {
            store.register("owner", tableOwner);
            try (var filler =
                    interpretedInstance(
                            """
                            (module
                              (import "owner" "a" (table 4 funcref))
                              (global $g i32 (i32.const 42))
                              (func $answer (result i32) global.get $g)
                              (elem (i32.const 0) $answer))
                            """,
                            store)) {
                tableOwner.export("copyAToB").apply();
                assertEquals(42, call(tableOwner, "callB", 0));
                assertSame(filler, tableOwner.table(1).instance(0));

                tableOwner.export("shiftA").apply();
                assertEquals(42, call(tableOwner, "callA", 0));
                assertEquals(42, call(tableOwner, "callA", 1));
                assertSame(filler, tableOwner.table(0).instance(1));
                assertNull(tableOwner.table(0).instance(2), "shifted in from a null entry");
            }
        }
    }

    @Test
    public void hostWritesRecordTheOwner() {
        var store = storeWithHost();
        try (var owner = nativeInstance(OWNER, store);
                var holder = nativeInstance(HOLDER, store)) {
            var table = holder.table(0);
            table.setRef(0, 1, owner); // $readGlobal
            assertEquals(42, call(holder, "call", 0));
            assertEquals(2, table.grow(1, 2, owner)); // $readMemory
            assertEquals(1000, call(holder, "call", 2));
            assertSame(owner, table.instance(2));
        }
    }

    /** table.set, table.grow and table.fill run natively in the instance writing the entry. */
    @Test
    public void nativeWritesByAnotherInstance() {
        var store = new Store();
        try (var holder = nativeInstance(HOLDER, store)) {
            store.register("holder", holder);
            try (var setter =
                    nativeInstance(
                            """
                            (module
                              (import "holder" "tab" (table 2 funcref))
                              (global $g i32 (i32.const 42))
                              (func $answer (result i32) global.get $g)
                              (elem declare func $answer)
                              (func (export "set") (param i32)
                                (table.set (local.get 0) (ref.func $answer)))
                              (func (export "grow") (result i32)
                                (table.grow (ref.func $answer) (i32.const 1)))
                              (func (export "fill")
                                (table.fill (i32.const 0) (ref.func $answer) (i32.const 2))))
                            """,
                            store)) {
                setter.export("set").apply(0);
                assertEquals(42, call(holder, "call", 0));
                assertSame(setter, holder.table(0).instance(0));

                assertEquals(2, (int) setter.export("grow").apply()[0]);
                assertEquals(42, call(holder, "call", 2));

                setter.export("fill").apply();
                assertEquals(42, call(holder, "call", 1));
            }
        }
    }

    /** A new instance may get a closed owner's context address, and must not take its entries. */
    @Test
    public void closedOwnerTrapsInsteadOfRunningAnotherInstance() {
        var store = new Store();
        try (var holder = nativeInstance(HOLDER, store)) {
            store.register("holder", holder);
            var writer = nativeInstance(WRITER, store);
            assertEquals(42, call(holder, "call", 0));
            writer.close();

            try (var reader = nativeInstance(READER, store);
                    var interpretedReader = interpretedInstance(READER, store)) {
                assertSame(writer, holder.table(0).instance(0));
                assertClosed(() -> call(reader, "call", 0));
                assertClosed(() -> call(holder, "call", 0));
                assertClosed(() -> call(interpretedReader, "call", 0));
            }
        }
    }

    /** The table drops closed instances that none of its entries refer to. */
    @Test
    public void tableReleasesClosedWriters() {
        var store = new Store();
        try (var holder = nativeInstance(HOLDER, store)) {
            store.register("holder", holder);
            for (int i = 0; i < 50; i++) {
                nativeInstance(WRITER, store).close();
            }
            var table = (NativeTable) holder.table(0);
            assertTrue(table.writerCount() <= 8, table.writerCount() + " writers kept");
            assertClosed(() -> call(holder, "call", 0));
        }
    }

    /** An image may leave functions out, which a table must not jump to. */
    @Test
    public void uncompiledFunctionIsNotCalled() {
        var module =
                parse(
                        """
                        (module
                          (type $i32 (func (result i32)))
                          (table 2 funcref)
                          (func $uncompiled (result i32) (i32.const 7))
                          (elem (i32.const 0) $uncompiled)
                          (func (export "callElem") (result i32)
                            (call_indirect (type $i32) (i32.const 0)))
                          (func (export "setAndCall") (result i32)
                            (table.set (i32.const 1) (ref.func $uncompiled))
                            (call_indirect (type $i32) (i32.const 1))))
                        """);
        try (var instance =
                NativeMachineFactory.builder(module)
                        .withPrecompiledCode(withoutFirstBody(compile(module)))
                        .build()) {
            var e =
                    assertThrows(
                            WasmEngineException.class, () -> instance.export("callElem").apply());
            assertTrue(e.getMessage().contains("not compiled"), e.getMessage());
            e =
                    assertThrows(
                            WasmEngineException.class, () -> instance.export("setAndCall").apply());
            assertTrue(e.getMessage().contains("uninitialized element"), e.getMessage());
        }
    }

    /**
     * Has the caller descend {@code levels} before calling the owner, until
     * the stack runs out.
     *
     * @return how many levels {@code counted} recursed
     */
    private static int depthReached(Instance caller, int levels, Instance counted) {
        var depth = counted.exports().global("depth");
        int before = (int) depth.getValue();
        var e = assertThrows(WasmEngineException.class, () -> call(caller, "descend", levels));
        assertTrue(e.getMessage().contains("call stack exhausted"), e.getMessage());
        return (int) depth.getValue() - before;
    }

    private static void assertClosed(Runnable call) {
        var e = assertThrows(WasmEngineException.class, call::run);
        assertTrue(e.getMessage().contains("instance is closed"), e.getMessage());
    }

    /** The owner and caller instances, closed caller first. */
    private record Linked(Instance owner, Instance caller) implements AutoCloseable {
        @Override
        public void close() {
            caller.close();
            owner.close();
        }
    }

    /**
     * Instantiates the owner and the caller natively, with the caller importing
     * the owner's table.
     *
     * @return the linked instances, to close after the test
     */
    private static Linked linkNative() {
        var store = storeWithHost();
        var owner = nativeInstance(OWNER, store);
        store.register("owner", owner);
        var caller = nativeInstance(CALLER, store);
        return new Linked(owner, caller);
    }

    /** A store with the owner's host import, which returns 7. */
    private static Store storeWithHost() {
        var seven =
                new HostFunction(
                        "host",
                        "seven",
                        FunctionType.of(List.of(), List.of(ValType.I32)),
                        (inst, args) -> new long[] {7});
        return new Store().addFunction(seven);
    }

    /**
     * Calls an export that takes a table index and returns an i32.
     *
     * @param instance the instance to call
     * @param export the export's name
     * @param index the table index to pass
     * @return the result
     */
    private static int call(Instance instance, String export, int index) {
        return (int) instance.export(export).apply(index)[0];
    }

    /** Instantiates {@code wat} natively against the store's exports. */
    private static Instance nativeInstance(String wat, Store store) {
        return NativeInstanceBuilder.builder(parse(wat))
                .withImportValues(store.toImportValues())
                .build();
    }

    /** Instantiates {@code wat} in the interpreter against the store's exports. */
    private static Instance interpretedInstance(String wat, Store store) {
        return Instance.builder(parse(wat)).withImportValues(store.toImportValues()).build();
    }

    private static NativeCode compile(WasmModule module) {
        return NativeCompiler.compile(RedlineTarget.detectHost().orElseThrow().triple(), module);
    }

    /** Returns {@code code} with its first function body left uncompiled. */
    private static NativeCode withoutFirstBody(NativeCode code) {
        int[] bodies = new int[code.functionBodyCount()];
        int[] entries = new int[code.functionBodyCount()];
        for (int i = 0; i < bodies.length; i++) {
            bodies[i] = i == 0 ? NativeCode.NOT_COMPILED : code.bodyOffset(i);
            entries[i] = i == 0 ? NativeCode.NOT_COMPILED : code.entryTrampolineOffset(i);
        }
        int[] importTrampolines = new int[code.importCount()];
        int[] importSlots = new int[code.importCount()];
        for (int i = 0; i < importTrampolines.length; i++) {
            importTrampolines[i] = code.importTrampolineOffset(i);
            importSlots[i] = code.importStubSlotOffset(i);
        }
        return new NativeCode(
                code.triple(), code.image(), bodies, entries, importTrampolines, importSlots);
    }

    /** Parses a module from WebAssembly text format. */
    private static WasmModule parse(String wat) {
        return Parser.parse(Wat2Wasm.parse(wat));
    }
}
