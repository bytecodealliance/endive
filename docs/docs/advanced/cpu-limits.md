---
sidebar_position: 1
sidebar_label: CPU
title: CPU
---
# Limiting CPU usage

:::warning[Security Consideration]
Wasm modules can contain infinite loops. When running untrusted code, always set execution timeouts via thread interruption or an ExecutorService with a deadline. Without timeouts, a malicious module can consume 100% CPU indefinitely.
:::

Often, when running untrusted user code in our infrastructure, we want to have strong guarantees around the termination of the program.

To achieve this result there are, currently, three mechanisms in Endive:

## Interrupts

Wasm modules executed using Endive honour the carrier thread interruption mechanism, thus you can leverage it to implement absolute timeouts:

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
Thread.sleep(200);
thread.interrupt();
```

Or using an `ExecutorService`:

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

ExecutorService service = Executors.newSingleThreadExecutor();
var future = service.submit(() -> function.apply());
try {
  future.get(100, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
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

## Fuel

Interrupts let you stop a program once you decide to act, but they cannot express "this program may run for at most so much work". Fuel can: you give the current thread a budget of work, and execution stops with a `WasmOutOfFuelException` once it is spent.

A budget is spent by doing work rather than by time passing, so it does not move with how busy the machine is: the same module given the same fuel gets the same distance every time.

```java
import run.endive.runtime.Fuel;
import run.endive.runtime.WasmOutOfFuelException;

var worker = new Thread(() -> {
    Fuel.set(1_000_000);
    try {
        function.apply();
    } catch (WasmOutOfFuelException e) {
        // the module outstayed its budget
    } finally {
        Fuel.clear();
    }
});
worker.start();
worker.join();
```

The budget belongs to the thread that set it, because interruption does too, so running one module per thread gives you one budget per module with no further bookkeeping. That is why the budget and the call it bounds sit together inside the worker above: set a budget on one thread and run the module on another, and the module is simply unmetered.

Fuel is consumed wherever the engine already checks for interruption — backward jumps and calls — so it is not per-instruction accounting. It is enough to bound a loop that would otherwise never end, and unlike the execution listener it keeps working when the module is run through the compiler.

Fuel bounds work, not memory. It will stop a module that computes forever; it will not stop one that allocates heavily, which is a separate problem with separate mechanisms.

Modules that never use fuel are unaffected. Metering is off until some thread calls `Fuel.set`, and while it is off the check reads one static field, on a path that already does more work than that.


<!--
```java
docs.FileOps.writeResult("docs/advanced", "cpu-limits.md.result", "empty");
```
-->
