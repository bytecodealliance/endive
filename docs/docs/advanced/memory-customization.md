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

Endive ships two implementations of linear memory, and `withMemoryFactory` selects which one backs an instance:

```java
var instance = Instance.builder(module).
        withMemoryFactory(ByteArrayMemory::new).
        build();
```

## Choosing an implementation

| Implementation | Plain loads and stores | Atomic instructions | Works on |
|---|---|---|---|
| `ByteBufferMemory` (default) | Good | Always take a lock | Every JDK and Android |
| `ByteArrayMemory` | Fastest on every JDK | Lock-free up to JDK 21, locked from JDK 23 | Every JDK |

Atomic instructions only matter for modules built with the threads proposal that share their memory between threads.
`ByteArrayMemory` is the faster choice on a standard JVM, and `ByteBufferMemory` is the safe default where that cannot be assumed, such as Android.
From JDK 23 the JDK no longer provides lock-free multi-byte atomics on byte arrays, so a shared memory performs the same with either implementation there.

Workloads use memory in very different ways, so benchmark before changing the default.

[Redline](../experimental/redline.md#native-memory) keeps linear memory off-heap with lock-free atomics; that memory comes with the Redline runners and is not selectable here.

Any class implementing `Memory` can be passed to `withMemoryFactory`, for example an off-heap implementation of your own.
The factory receives the module's declared limits, or the ones set with [`withMemoryLimits`](../core/memory.md#limiting-memory-growth).

<!--
```java
docs.FileOps.writeResult("docs/advanced", "memory-customization.md.result", "empty");
```
-->
