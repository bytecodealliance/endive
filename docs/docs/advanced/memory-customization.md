---
sidebar_position: 5
sidebar_label: Memory
title: Advanced Wasm Memory Customization
---
<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "count_vowels.rs.wasm");

System.setOut(new PrintStream(
  new BufferedOutputStream(
    new FileOutputStream("docs/examples/rust.md.result"))));

import run.endive.wasm.Parser;
import run.endive.runtime.Instance;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ByteBufferMemory;

var module = Parser.parse(new File("count_vowels.rs.wasm"));
```
-->

Different Wasm workloads will be using the memory in very different ways.

Make sure you don't do changes without proper benchmarking and analysis.

It's possible to provide a custom implementation of the entire Memory used by the Wasm module:

```java
var instance = Instance.builder(module).withMemoryFactory(limits -> {
        return new ByteArrayMemory(limits);
    }).build();
```

:::note
Endive provides two built-in Memory implementations:
- `ByteArrayMemory`: An optimized memory implementation, recommended for recent OpenJDK systems
- `ByteBufferMemory`: Recommended for different Java runtimes (in particular, on Android VMs)
:::

<!--
```java
docs.FileOps.writeResult("docs/advanced", "memory-customization.md.result", "empty");
```
-->
