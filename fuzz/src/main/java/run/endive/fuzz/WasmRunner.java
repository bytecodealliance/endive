package run.endive.fuzz;

import java.io.File;
import java.util.List;

@FunctionalInterface
public interface WasmRunner {
    String run(File wasmFile, String functionName, List<String> params) throws Exception;
}
