package run.endive.redline.experimental.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import run.endive.redline.experimental.api.internal.Borrowers;
import run.endive.redline.experimental.api.internal.NativeResource;
import run.endive.runtime.ByteBufferMemory;
import run.endive.runtime.TableInstance;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.MutabilityType;
import run.endive.wasm.types.Table;
import run.endive.wasm.types.TableLimits;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/**
 * With no native provider the factory has to hand back the ordinary runtime types,
 * which is what a platform redline does not compile for ends up running.
 */
public class ImportFactoryTest {

    private static final ImportFactory BYTECODE = ImportFactory.forBytecode();

    @Test
    public void withoutAProviderItBuildsTheBytecodeTypes() {
        assertFalse(BYTECODE.isNative());
        assertInstanceOf(ByteBufferMemory.class, BYTECODE.memory(new MemoryLimits(1, 2)));
        assertInstanceOf(
                TableInstance.class,
                BYTECODE.table(
                        new Table(ValType.FuncRef, new TableLimits(1, 1)), Value.REF_NULL_VALUE));
    }

    @Test
    public void aModuleWithoutNativeCodeGetsTheBytecodeTypes() {
        assertFalse(ImportFactory.forNativeCode(null).isNative());
    }

    @Test
    public void aBytecodeGlobalKeepsItsValueAndMutability() {
        var global = BYTECODE.global(Value.i32(7), MutabilityType.Var);

        assertEquals(7, global.getValue());
        assertEquals(MutabilityType.Var, global.getMutabilityType());
        assertEquals(ValType.I32, global.getType());
    }

    @Test
    public void closingFreesWhatItMadeAllOrNothing() {
        var imports = ImportFactory.forNative(new TableProvider());
        var first = (FakeTable) imports.table(tableType(), Value.REF_NULL_VALUE);
        var second = (FakeTable) imports.table(tableType(), Value.REF_NULL_VALUE);
        var borrower = new Object();
        second.borrowers().add(borrower, "A table");

        assertThrows(IllegalStateException.class, imports::close);
        assertEquals(0, first.closes, "refusing frees nothing");

        second.borrowers().remove(borrower);
        imports.close();
        imports.close();
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
        assertThrows(
                IllegalStateException.class,
                () -> imports.table(tableType(), Value.REF_NULL_VALUE));
    }

    @Test
    public void closingTheBytecodeFactoryHasNothingToFree() {
        var imports = ImportFactory.forBytecode();
        var memory = imports.memory(new MemoryLimits(1, 1));
        imports.close();
        memory.writeI32(0, 1);
        assertEquals(1, memory.readInt(0));
    }

    @Test
    public void aBytecodeTableStartsOnItsInitialiser() {
        var table =
                BYTECODE.table(
                        new Table(ValType.FuncRef, new TableLimits(2, 2)), Value.REF_NULL_VALUE);

        assertEquals(2, table.size());
        assertEquals(Value.REF_NULL_VALUE, table.ref(0));
    }

    private static Table tableType() {
        return new Table(ValType.FuncRef, new TableLimits(1, 1));
    }

    private static final class FakeTable extends TableInstance implements NativeResource {
        private final Borrowers borrowers = new Borrowers();
        int closes;

        FakeTable(Table table, int initValue) {
            super(table, initValue);
        }

        @Override
        public Borrowers borrowers() {
            return borrowers;
        }

        @Override
        public void close() {
            closes++;
            borrowers.freed();
        }
    }

    public static final class TableProvider extends NativeMachineFactoryProviderTest.LowProvider {
        @Override
        public TableInstance createImportTable(Table table, int initValue) {
            return new FakeTable(table, initValue);
        }
    }
}
