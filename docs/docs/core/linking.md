---
sidebar_position: 3
sidebar_label: Linking
title: Linking
---
A [Store](https://www.w3.org/TR/2019/REC-wasm-core-1-20191205/#store%E2%91%A0) collects host functions, globals, memories, tables and named instances, so that modules can import from each other.

In the simplest case it registers single host functions, globals, memories and tables, as in the `console.log` example from [Host Functions](host-functions.md):

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT
```
-->

```java
import run.endive.wasm.Parser;
import run.endive.runtime.Instance;
import run.endive.runtime.HostFunction;
import run.endive.runtime.Store;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.FunctionType;

var func = new HostFunction(
    "console",
    "log",
    FunctionType.of(
        List.of(ValType.I32, ValType.I32),
        List.of()
    ),
    (Instance instance, long... args) -> { // decompiled is: console_log(13, 0);
        var len = (int) args[0];
        var offset = (int) args[1];
        var message = instance.memory().readString(offset, len);
        println(message);
        return null;
    });

// instantiate the store
var store = new Store();
// registers `console.log` in the store
store.addFunction(func);
```

However, the store also automatically exposes the exports of a module to the other instances that are registered. In fact, in the [Host Functions section](host-functions.md), when we created our instance from the `logger.wasm` module, we also passed a string `"logger"`. This is the name of the instance:

```java
// create a named `instance` with name `logger`
var instance = store.instantiate("logger", Parser.parse(new File("./logger.wasm")));
```

Because this instance is now named, now any exports in the `logger` module will be automatically qualified.
For instance, the exported function `logIt` will be visible by other modules as `logger.logIt`.

## Notes

- The invocation `store.instantiate("logger", ...)` is in fact equivalent to the lower-level sequence:

    ```java
    var imports = store.toImportValues();
    var m = Parser.parse(new File("./logger.wasm"));
    var instance = Instance.builder(m).withImportValues(imports).build();
    store.register("logger", instance);
    ```

    However, in most cases we recommend to use the shorthand form.

- Registering a second instance under a name already in use overwrites the functions, globals,
memories and tables with matching names.

- The current `Store` is a mutable object, not meant to be shared (it is not thread-safe).

- A `Store` _does not_ resolve interdependencies between modules in itself: if your set of modules
have interdependencies, you will have to instantiate and register them in the right order.

<!--
```java
docs.FileOps.writeResult("docs/core", "linking.md.result", "empty");
```
-->
