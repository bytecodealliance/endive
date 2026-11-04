---
sidebar_position: 5
sidebar_label: Memory Customization
title: Memory Customization
---
<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "count_vowels.rs.wasm");

import run.endive.wasm.Parser;
import run.endive.runtime.Instance;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ByteBufferMemory;

var module = Parser.parse(new File("count_vowels.rs.wasm"));
```
-->

You can replace the `Memory` implementation backing a module through `withMemoryFactory`.
Workloads use memory in very different ways, so benchmark before changing it:

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
