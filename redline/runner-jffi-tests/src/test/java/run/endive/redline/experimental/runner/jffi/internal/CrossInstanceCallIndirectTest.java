package run.endive.redline.experimental.runner.jffi.internal;

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
import run.endive.redline.experimental.runner.jffi.JffiNativeMachineFactory;
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
            "(module\n"
                + "  (import \"host\" \"seven\" (func $host (result i32)))\n"
                + "  (memory 1)\n"
                + "  (global $g (mut i32) (i32.const 42))\n"
                + "  (table (export \"tab\") 6 funcref)\n"
                + "  (data (i32.const 16) \"\\e8\\03\\00\\00\")\n"
                + "  (func $readGlobal (result i32) global.get $g)\n"
                + "  (func $readMemory (result i32) (i32.load (i32.const 16)))\n"
                + "  (func $trap (result i32) unreachable)\n"
                + "  (func $pair (result i32 i32) global.get $g (i32.load (i32.const 16)))\n"
                + "  (func $bumpGlobal (result i32)\n"
                + "    (global.set $g (i32.add (global.get $g) (i32.const 1)))\n"
                + "    global.get $g)\n"
                + "  (elem (i32.const 0) $readGlobal $readMemory $host $trap $pair $bumpGlobal))\n";

    /**
     * Calls through the owner's table. Its own global and memory differ from
     * the owner's, and its types are declared in a different order, so type
     * indices differ between the two.
     */
    private static final String CALLER =
            "(module\n"
                    + "  (type $byIndex (func (param i32) (result i32)))\n"
                    + "  (type $i64 (func (result i64)))\n"
                    + "  (type $pair (func (result i32 i32)))\n"
                    + "  (type $i32 (func (result i32)))\n"
                    + "  (import \"owner\" \"tab\" (table 6 funcref))\n"
                    + "  (memory 1)\n"
                    + "  (global $g (mut i32) (i32.const -1))\n"
                    + "  (func (export \"call\") (type $byIndex)\n"
                    + "    (call_indirect (type $i32) (local.get 0)))\n"
                    + "  (func (export \"tailCall\") (type $byIndex)\n"
                    + "    (return_call_indirect (type $i32) (local.get 0)))\n"
                    + "  (func (export \"callPair\") (result i32)\n"
                    + "    (call_indirect (type $pair) (i32.const 4))\n"
                    + "    i32.sub)\n"
                    + "  (func (export \"wrongType\") (result i64)\n"
                    + "    (call_indirect (type $i64) (i32.const 0))))\n";

    /** Defines an empty table and calls through it. */
    private static final String HOLDER =
            "(module\n"
                    + "  (type $i32 (func (result i32)))\n"
                    + "  (table (export \"tab\") 2 4 funcref)\n"
                    + "  (func (export \"call\") (param i32) (result i32)\n"
                    + "    (call_indirect (type $i32) (local.get 0))))\n";

    /** Puts its function, which returns 42, in the holder's table. */
    private static final String WRITER =
            "(module\n"
                    + "  (import \"holder\" \"tab\" (table 2 funcref))\n"
                    + "  (global $g i32 (i32.const 42))\n"
                    + "  (func $answer (result i32) global.get $g)\n"
                    + "  (elem (i32.const 0) $answer))\n";

    /**
     * Calls through the holder's table. Its own function has the same index as
     * the writer's but returns -1.
     */
    private static final String READER =
            "(module\n"
                    + "  (type $i32 (func (result i32)))\n"
                    + "  (import \"holder\" \"tab\" (table 2 funcref))\n"
                    + "  (global $g i32 (i32.const -1))\n"
                    + "  (func $own (result i32) global.get $g)\n"
                    + "  (func (export \"call\") (param i32) (result i32)\n"
                    + "    (call_indirect (type $i32) (local.get 0))))\n";

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
                        "(module\n"
                                + "  (import \"host\" \"none\" (func $none (result i32 i32)))\n"
                                + "  (table (export \"tab\") 1 funcref)\n"
                                + "  (elem (i32.const 0) $none))\n",
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            "(module\n"
                                    + "  (type $pair (func (result i32 i32)))\n"
                                    + "  (import \"owner\" \"tab\" (table 1 funcref))\n"
                                    + "  (func (export \"call\")\n"
                                    + "    (call_indirect (type $pair) (i32.const 0))\n"
                                    + "    drop\n"
                                    + "    drop))\n",
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
                        "(module\n"
                                + "  (memory (export \"mem\") 1)\n"
                                + "  (table (export \"tab\") 1 funcref)\n"
                                + "  (func $grow (result i32) (memory.grow (i32.const 1)))\n"
                                + "  (elem (i32.const 0) $grow))\n",
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            "(module\n"
                                    + "  (type $i32 (func (result i32)))\n"
                                    + "  (import \"owner\" \"mem\" (memory 1))\n"
                                    + "  (import \"owner\" \"tab\" (table 1 funcref))\n"
                                    + "  (func (export \"growThenStore\") (result i32)\n"
                                    + "    (drop (call_indirect (type $i32) (i32.const 0)))\n"
                                    + "    (i32.store (i32.const 65536) (memory.size))\n"
                                    + "    (i32.load (i32.const 65536))))\n",
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
                        "(module\n"
                                + "  (global (export \"depth\") (mut i32) (i32.const 0))\n"
                                + "  (table (export \"tab\") 1 funcref)\n"
                                + "  (func $recurse (result i32)\n"
                                + "    (global.set 0 (i32.add (global.get 0) (i32.const 1)))\n"
                                + "    (call $recurse))\n"
                                + "  (elem (i32.const 0) $recurse))\n",
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            "(module\n"
                                + "  (type $i32 (func (result i32)))\n"
                                + "  (import \"owner\" \"tab\" (table 1 funcref))\n"
                                + "  (global (export \"depth\") (mut i32) (i32.const 0))\n"
                                + "  (func $descend (export \"descend\") (param i32) (result i32)\n"
                                + "    (global.set 0 (i32.add (global.get 0) (i32.const 1)))\n"
                                + "    (if (result i32) (local.get 0)\n"
                                + "      (then\n"
                                + "        (call $descend (i32.sub (local.get 0) (i32.const 1))))\n"
                                + "      (else (call_indirect (type $i32) (i32.const 0))))))\n",
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
                        "(module\n"
                                + "  (global (export \"depth\") (mut i32) (i32.const 0))\n"
                                + "  (func $recurse (export \"recurse\") (result i32)\n"
                                + "    (global.set 0 (i32.add (global.get 0) (i32.const 1)))\n"
                                + "    (call $recurse)))\n",
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            "(module\n"
                                + "  (import \"owner\" \"recurse\" (func $recurse (result i32)))\n"
                                + "  (global (export \"depth\") (mut i32) (i32.const 0))\n"
                                + "  (func $descend (export \"descend\") (param i32) (result i32)\n"
                                + "    (global.set 0 (i32.add (global.get 0) (i32.const 1)))\n"
                                + "    (if (result i32) (local.get 0)\n"
                                + "      (then\n"
                                + "        (call $descend (i32.sub (local.get 0) (i32.const 1))))\n"
                                + "      (else (call $recurse)))))\n",
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
                        "(module\n"
                                + "  (type $super (sub (func (result funcref))))\n"
                                + "  (type $sub (sub $super (func (result (ref func)))))\n"
                                + "  (table (export \"tab\") 1 funcref)\n"
                                + "  (func $f (type $sub) (ref.func $f))\n"
                                + "  (elem (i32.const 0) $f))\n",
                        store)) {
            store.register("owner", owner);
            try (var caller =
                    nativeInstance(
                            "(module\n"
                                    + "  (type $super (sub (func (result funcref))))\n"
                                    + "  (import \"owner\" \"tab\" (table 1 funcref))\n"
                                    + "  (func (export \"call\") (result i32)\n"
                                    + "    (ref.is_null (call_indirect (type $super) (i32.const"
                                    + " 0)))))\n",
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
                        "(module\n"
                                + "  (type $i32 (func (result i32)))\n"
                                + "  (table (export \"tab\") 2 funcref)\n"
                                + "  (global $g i32 (i32.const -1))\n"
                                + "  (func $own (result i32) global.get $g)\n"
                                + "  (elem (i32.const 1) $own)\n"
                                + "  (func (export \"call\") (param i32) (result i32)\n"
                                + "    (call_indirect (type $i32) (local.get 0))))\n",
                        store)) {
            store.register("owner", tableOwner);
            try (var filler =
                    interpretedInstance(
                            "(module\n"
                                    + "  (import \"owner\" \"tab\" (table 2 funcref))\n"
                                    + "  (global $g i32 (i32.const 42))\n"
                                    + "  (func $answer (result i32) global.get $g)\n"
                                    + "  (elem (i32.const 0) $answer))\n",
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
                        "(module\n"
                                + "  (global $g i32 (i32.const 42))\n"
                                + "  (func $answer (result i32) global.get $g)\n"
                                + "  (table (export \"tab\") 2 funcref (ref.func $answer)))\n",
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
                            "(module\n"
                                    + "  (type $i32 (func (result i32)))\n"
                                    + "  (import \"owner\" \"tab\" (table $imported 6 funcref))\n"
                                    + "  (table $own 6 funcref)\n"
                                    + "  (func (export \"copy\")\n"
                                    + "    (table.copy $own $imported\n"
                                    + "      (i32.const 0) (i32.const 0) (i32.const 6)))\n"
                                    + "  (func (export \"call\") (param i32) (result i32)\n"
                                    + "    (call_indirect $own (type $i32) (local.get 0))))\n",
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
                        "(module\n"
                            + "  (type $i32 (func (result i32)))\n"
                            + "  (table $a (export \"a\") 4 funcref)\n"
                            + "  (table $b 4 funcref)\n"
                            + "  (func (export \"copyAToB\")\n"
                            + "    (table.copy $b $a (i32.const 0) (i32.const 0) (i32.const 4)))\n"
                            + "  (func (export \"shiftA\")\n"
                            + "    (table.copy $a $a (i32.const 1) (i32.const 0) (i32.const 3)))\n"
                            + "  (func (export \"callA\") (param i32) (result i32)\n"
                            + "    (call_indirect $a (type $i32) (local.get 0)))\n"
                            + "  (func (export \"callB\") (param i32) (result i32)\n"
                            + "    (call_indirect $b (type $i32) (local.get 0))))\n",
                        store)) {
            store.register("owner", tableOwner);
            try (var filler =
                    interpretedInstance(
                            "(module\n"
                                    + "  (import \"owner\" \"a\" (table 4 funcref))\n"
                                    + "  (global $g i32 (i32.const 42))\n"
                                    + "  (func $answer (result i32) global.get $g)\n"
                                    + "  (elem (i32.const 0) $answer))\n",
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
                            "(module\n"
                                    + "  (import \"holder\" \"tab\" (table 2 funcref))\n"
                                    + "  (global $g i32 (i32.const 42))\n"
                                    + "  (func $answer (result i32) global.get $g)\n"
                                    + "  (elem declare func $answer)\n"
                                    + "  (func (export \"set\") (param i32)\n"
                                    + "    (table.set (local.get 0) (ref.func $answer)))\n"
                                    + "  (func (export \"grow\") (result i32)\n"
                                    + "    (table.grow (ref.func $answer) (i32.const 1)))\n"
                                    + "  (func (export \"fill\")\n"
                                    + "    (table.fill (i32.const 0) (ref.func $answer) (i32.const"
                                    + " 2))))\n",
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
            var table = (JffiNativeTable) holder.table(0);
            assertTrue(table.writerCount() <= 8, table.writerCount() + " writers kept");
            assertClosed(() -> call(holder, "call", 0));
        }
    }

    /** An image may leave functions out, which a table must not jump to. */
    @Test
    public void uncompiledFunctionIsNotCalled() {
        var module =
                parse(
                        "(module\n"
                                + "  (type $i32 (func (result i32)))\n"
                                + "  (table 2 funcref)\n"
                                + "  (func $uncompiled (result i32) (i32.const 7))\n"
                                + "  (elem (i32.const 0) $uncompiled)\n"
                                + "  (func (export \"callElem\") (result i32)\n"
                                + "    (call_indirect (type $i32) (i32.const 0)))\n"
                                + "  (func (export \"setAndCall\") (result i32)\n"
                                + "    (table.set (i32.const 1) (ref.func $uncompiled))\n"
                                + "    (call_indirect (type $i32) (i32.const 1))))\n");
        try (var instance =
                JffiNativeMachineFactory.builder(module)
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
    private static final class Linked implements AutoCloseable {
        final Instance owner;
        final Instance caller;

        Linked(Instance owner, Instance caller) {
            this.owner = owner;
            this.caller = caller;
        }

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
