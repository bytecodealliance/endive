package run.endive.fuzz;

import java.io.File;
import java.util.List;
import java.util.function.Function;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.runtime.WasmInterruptedException;
import run.endive.wasm.Parser;

public class ChicoryRunner implements WasmRunner {

    private final Function<Instance, Machine> machineFactory;

    public ChicoryRunner() {
        this(null);
    }

    public ChicoryRunner(Function<Instance, Machine> machineFactory) {
        this.machineFactory = machineFactory;
    }

    @Override
    public String run(File wasmFile, String functionName, List<String> params) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new WasmInterruptedException("Thread interrupted");
        }
        var module = Parser.parse(wasmFile);
        var builder = Instance.builder(module).withInitialize(true).withStart(false);
        if (machineFactory != null) {
            builder.withMachineFactory(machineFactory);
        }
        var instance = builder.build();

        var type = instance.exportType(functionName);
        var export = instance.export(functionName);
        var longParams = new long[type.params().size()];
        for (var i = 0; i < type.params().size(); i++) {
            longParams[i] = Long.parseLong(params.get(i));
        }

        var result = export.apply(longParams);
        var sb = new StringBuilder();
        if (result != null) {
            for (var r : result) {
                sb.append(r).append("\n");
            }
        }
        return sb.toString();
    }
}
