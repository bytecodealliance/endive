---
sidebar_position: 1
sidebar_label: Host Functions
title: Host Functions
---
A host function is a Java function that a Wasm module imports and calls, and it is the way a module reaches anything outside its sandbox.

:::warning[Security Consideration]
Host functions cross the sandbox boundary: validate every argument that comes from Wasm, especially memory offsets and lengths, before touching host resources. See [Security Best Practices](/docs/security/best-practices).
:::

In Wasm terms the module instance is the **guest** and the surrounding runtime, your Java application, is the **host**.
A module can **export** functions for the host to call, and **import** functions that the host must provide when the module is instantiated.
Without imports a module is pure compute: it cannot perform I/O or interact with other modules.

## Writing a host function

A host function is called from Wasm but runs in the JVM, so it can do anything your Java code can.
That is also what makes it a security boundary when the module is untrusted.

Download the following module, which imports a `console.log` function:

```bash
curl https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/host-function.wat.wasm > logger.wasm
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

docs.FileOps.copyFromWasmCorpus("host-function.wat.wasm", "logger.wasm");
```
-->

This module expects us to fulfil an import with the name `console.log`. 
As the name implies, this function allows a caller to log a message to standard output.
We could write it as the host function:

<!--
```java
System.setOut(new PrintStream(
  new BufferedOutputStream(
    new FileOutputStream("docs/core/host-functions.md.result"))));
```
-->


```java
import run.endive.runtime.Instance;
import run.endive.runtime.HostFunction;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.FunctionType;

var func = new HostFunction(
    "console",
    "log",
    FunctionType.of(
        List.of(ValType.I32, ValType.I32),
        List.of()
    ),
    (Instance instance, long... args) -> {
        var len = (int) args[0];
        var offset = (int) args[1];
        var message = instance.memory().readString(offset, len);
        System.out.println(message);
        return null;
    });
```

The module calls `console.log` with the length of the string and an index (offset) in its memory. 
This is essentially a pointer into the Wasm's linear memory.
The `Instance` class provides a reference to a `Memory` object instance, that allows 
to pull a value out of memory. You can use the `readString()` convenience method
to read a buffer into a `String`; we can then print that to stdout on behalf of our Wasm program.

Note that the `HostFunction` needs 3 things:

1. The namespace and function name of the import (in our case it's `console` and `log` respectively)
2. The Wasm type signature (this function takes two `i32`s as arguments and returns nothing)
3. A lambda to call when the Wasm module invokes the import


We now need to pass this host function when we instantiate the module.
We can do so by using a `Store`:

```java
import run.endive.wasm.Parser;
import run.endive.runtime.Store;

// instantiate the store
var store = new Store();
// registers `console.log` in the store
store.addFunction(func);
var instance = store.instantiate("logger", Parser.parse(new File("./logger.wasm")));
var logIt = instance.export("logIt");
logIt.apply();
// should print "Hello, World!" 10 times
```

:::tip
For an easier way to write host function and interact with a Wasm module, see [Annotations](annotations.md).
:::
