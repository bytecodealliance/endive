---
sidebar_position: 3
sidebar_label: CPU Limits
title: CPU Limits
---

Wasm modules can loop forever, so untrusted code needs a timeout. Endive offers two mechanisms to bound execution.

## Interrupts

Endive checks the interrupt flag of the thread running the module, so interrupting that thread stops the execution with a `WasmInterruptedException`:

```bash
curl https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/infinite-loop.c.wasm > infinite-loop.wasm
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

docs.FileOps.copyFromWasmCorpus("infinite-loop.c.wasm", "infinite-loop.wasm");
```
-->

Build and instantiate this infinite loop module:

```java
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

Instance instance = Instance.builder(Parser.parse(new File("./infinite-loop.wasm"))).build();
ExportFunction function = instance.export("run");
```

Now you can execute the Wasm module and control the execution using plain interrupts, with the low level Thread API:

```java
var thread = new Thread() {
    @Override
    public void run() {
        function.apply();
    }
};
thread.start();
thread.join(200);
thread.interrupt();
```

Or using an `ExecutorService`:

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

ExecutorService service = Executors.newSingleThreadExecutor();
var future = service.submit(() -> function.apply());
try {
  future.get(100, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    future.cancel(true);
    // handle the failure
}
```

## [unsafe] Execution Listener

The Endive interpreter exposes an unsafe listener to granularly control the Wasm Modules execution.
Using it is extremely risky as the code will be evaluated for each and every Wasm instruction, use it with extreme caution.

```java
var instance =
    Instance.builder(Parser.parse(new File("./infinite-loop.wasm"))).withUnsafeExecutionListener(
        (instruction, stack) ->
            System.out.println("current instruction: " + instruction + ", stack size: " + stack.size())).build();
```

<!--
```java
docs.FileOps.writeResult("docs/security", "cpu-limits.md.result", "empty");
```
-->
