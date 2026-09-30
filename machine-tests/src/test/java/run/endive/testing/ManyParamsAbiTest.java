package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.CallResult;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.runtime.Store;
import run.endive.runtime.WasmFunctionHandle;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

/**
 * Functions with more than 251 parameter slots receive their arguments boxed in a long[] (and an
 * Object[] for object references). Their locals must still start at zero, object references must
 * still reach them, and the call_indirect and call_ref bridges, which take four extra JVM
 * parameters, must fit into a JVM method signature too.
 */
public class ManyParamsAbiTest {

    private static final int MAX_UNBOXED_PARAM_SLOTS = 251;

    private static final int PARAMS = 254;

    private static final Object HOST_OBJECT = new Object();

    private static Stream<Arguments> machineImplementations() {
        return Stream.of(
                Arguments.of(
                        "interpreter",
                        (Function<WasmModule, Function<Instance, Machine>>)
                                module -> InterpreterMachine::new),
                Arguments.of(
                        "compiler",
                        (Function<WasmModule, Function<Instance, Machine>>)
                                module -> MachineFactoryCompiler::compile),
                Arguments.of(
                        "compiler, boxed functions interpreted",
                        (Function<WasmModule, Function<Instance, Machine>>)
                                module ->
                                        MachineFactoryCompiler.builder(module)
                                                .withInterpretedFunctions(boxedFunctions(module))
                                                .compile()));
    }

    private static Set<Integer> boxedFunctions(WasmModule module) {
        int imports = module.importSection().count(ExternalType.FUNCTION);
        var functions = new HashSet<Integer>();
        for (int i = 0; i < module.functionSection().functionCount(); i++) {
            var type = module.functionSection().getFunctionType(i, module.typeSection());
            int slots = 0;
            for (var param : type.params()) {
                slots += param.equals(ValType.I64) || param.equals(ValType.F64) ? 2 : 1;
            }
            if (slots > MAX_UNBOXED_PARAM_SLOTS) {
                functions.add(imports + i);
            }
        }
        return functions;
    }

    private static WasmModule module(String name) {
        return Parser.parse(CorpusResources.getResource("compiled/" + name + ".wat.wasm"));
    }

    private static Instance instance(
            String name,
            Function<WasmModule, Function<Instance, Machine>> machine,
            ImportValues imports) {
        var module = module(name);
        return Instance.builder(module)
                .withMachineFactory(machine.apply(module))
                .withImportValues(imports)
                .build();
    }

    private static Instance instance(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        return instance(name, machine, ImportValues.empty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void localsStartAtZero(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        var instance = instance("many_params_locals", machine);
        assertEquals(10, instance.export("loop_acc").apply()[0]);
        assertEquals(0, instance.export("read_default").apply()[0]);
        assertEquals(0, instance.export("cond_write").apply()[0]);
        assertEquals(0, instance.export("first_local").apply()[0]);
        assertEquals(0, instance.export("last_local").apply()[0]);
        assertEquals(0L, instance.export("i64_default").apply()[0]);
        assertEquals(0L, instance.export("mixed_defaults").apply()[0]);
        assertEquals(41, instance.export("last_param").apply()[0]);
    }

    private static Instance refsInstance(
            Function<WasmModule, Function<Instance, Machine>> machine) {
        var params = new ArrayList<ValType>();
        params.add(ValType.ExternRef);
        params.addAll(Collections.nCopies(PARAMS, ValType.I32));
        var big =
                new HostFunction(
                        "host",
                        "big",
                        FunctionType.of(params, List.of(ValType.I32)),
                        new WasmFunctionHandle() {
                            @Override
                            public long[] apply(Instance instance, long... args) {
                                throw new UnsupportedOperationException("use applyWithRefs");
                            }

                            @Override
                            public CallResult applyWithRefs(
                                    Instance instance, long[] args, Object[] refArgs) {
                                long same = refArgs != null && refArgs[0] == HOST_OBJECT ? 1 : 0;
                                return CallResult.of(new long[] {same}, null);
                            }
                        });
        return instance(
                "many_params_refs", machine, ImportValues.builder().addFunction(big).build());
    }

    private static long applyWithHostObject(Instance instance, String export) {
        return instance.export(export)
                .applyWithRefs(new long[1], new Object[] {HOST_OBJECT})
                .longResult(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void gcRefArgumentsArePassed(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        var instance = refsInstance(machine);
        assertEquals(5, instance.export("struct_direct").apply()[0]);
        assertEquals(5, instance.export("struct_indirect").apply()[0]);
        assertEquals(5, instance.export("struct_call_ref").apply()[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void externRefArgumentsArePassed(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        var instance = refsInstance(machine);
        assertEquals(0, applyWithHostObject(instance, "extern_direct"));
        assertEquals(0, applyWithHostObject(instance, "extern_indirect"));
        assertEquals(0, applyWithHostObject(instance, "extern_tail"));
        assertEquals(1, applyWithHostObject(instance, "extern_host"));
        assertEquals(
                1,
                instance.export("extern_direct")
                        .applyWithRefs(new long[1], new Object[] {null})
                        .longResult(0));
        assertSame(
                HOST_OBJECT,
                instance.export("extern_identity")
                        .applyWithRefs(new long[1], new Object[] {HOST_OBJECT})
                        .refResult(0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void externRefArgumentsFromHost(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        var instance = refsInstance(machine);
        var refArgs = new Object[PARAMS + 1];
        refArgs[0] = HOST_OBJECT;
        assertEquals(
                0,
                instance.export("big_extern")
                        .applyWithRefs(new long[PARAMS + 1], refArgs)
                        .longResult(0));
        assertSame(
                HOST_OBJECT,
                instance.export("big_extern_identity")
                        .applyWithRefs(new long[PARAMS + 1], refArgs)
                        .refResult(0));
    }

    private static long[] firstPlusLastArgs(int count) {
        var args = new long[count];
        args[0] = 1;
        args[count - 1] = 41;
        return args;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void callsAroundTheBoxingThreshold(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        var instance = instance("many_params_slots", machine);
        for (int n = 251; n <= 254; n++) {
            assertEquals(42, instance.export("direct_" + n).apply()[0], "direct_" + n);
            assertEquals(42, instance.export("indirect_" + n).apply()[0], "indirect_" + n);
            assertEquals(42, instance.export("call_ref_" + n).apply()[0], "call_ref_" + n);
            assertEquals(42, instance.export("f" + n).apply(firstPlusLastArgs(n))[0], "f" + n);
        }
        for (String type : List.of("m252", "m253")) {
            assertEquals(42L, instance.export("direct_" + type).apply()[0], "direct_" + type);
            assertEquals(42L, instance.export("indirect_" + type).apply()[0], "indirect_" + type);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void chunkedCallIndirect(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        var instance = instance("many_params_chunked", machine);
        assertEquals(42L, instance.export("small").apply()[0]);
        assertEquals(42, applyWithHostObject(instance, "big"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("machineImplementations")
    public void crossModuleCallIndirect(
            String name, Function<WasmModule, Function<Instance, Machine>> machine) {
        var store = new Store();
        store.instantiate(
                "test", imports -> instance("many_params_cross_module-export", machine, imports));
        var instance =
                store.instantiate(
                        "import",
                        imports -> instance("many_params_cross_module-import", machine, imports));
        assertEquals(0, applyWithHostObject(instance, "extern_indirect"));
        assertEquals(42, instance.export("indirect_252").apply()[0]);
    }
}
