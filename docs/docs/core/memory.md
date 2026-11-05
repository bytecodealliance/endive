---
sidebar_position: 2
sidebar_label: Memory
title: Memory
---
`Memory` reads and writes a module's linear memory, which is how anything beyond integers and floats crosses the boundary.
Wasm functions only take and return numbers, so more complex values are passed as pointers into that memory.
Let's look at an example where we have a module `count_vowels.wasm`, written in Rust, that takes a string input and counts the number of vowels
in the string:

```bash
curl https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/count_vowels.rs.wasm > count_vowels.wasm
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "count_vowels.wasm");
```
-->

:::tip
A module like this one is a Rust library built with `rustc --target=wasm32-unknown-unknown --crate-type=cdylib`. Use `--target=wasm32-wasip1` for programs that need [WASI](../wasi/index.md).
:::

Build and instantiate this module:

```java
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

Instance instance = Instance.builder(Parser.parse(new File("./count_vowels.wasm"))).build();
ExportFunction countVowels = instance.export("count_vowels");
```

To pass it a string, we first need to write the string into the module's memory.
To make this easier and safe, the module gives us some extra exports to allow us allocate and deallocate memory:

```java
ExportFunction alloc = instance.export("alloc");
ExportFunction dealloc = instance.export("dealloc");
```

Let's allocate Wasm memory for a string and write it into the instance memory:

```java
import run.endive.runtime.Memory;

Memory memory = instance.memory();
String message = "Hello, World!";
byte[] bytes = message.getBytes();
int len = bytes.length;
// allocate {len} bytes of memory, this returns a pointer to that memory
int ptr = (int) alloc.apply(len)[0];
// We can now write the message to the module's memory:
memory.write(ptr, bytes);
```

Now we can call `countVowels` with this pointer to the string.
It will do its job and return the count.
We will call `dealloc` to free that memory in the module:

```java
var result = countVowels.apply(ptr, len)[0];
dealloc.apply(ptr, len);
assert(3L == result); // 3 vowels in Hello, World!
```

## Limiting memory growth

A module declares the initial and maximum size of its memory in 64 KiB pages, and the maximum defaults to 4 GiB when it declares none.
To cap how far an untrusted module can grow, override the limits of its memory when building the instance:

```java
import run.endive.wasm.types.MemoryLimits;

// keep the declared 17 initial pages, allow growth up to 32 pages (2 MiB)
var limited = Instance.builder(Parser.parse(new File("./count_vowels.wasm"))).
        withMemoryLimits(new MemoryLimits(17, 32)).
        build();
```

Past the maximum, `memory.grow` fails inside the module and the instance keeps running.
The override applies to the first memory the module defines; an imported memory keeps the limits it was created with.
Memory limits bound linear memory only: structs and arrays allocated by a [GC](gc-types.md) module live on the JVM heap and are bounded only by its size.
To change which implementation backs the memory, see [Memory Customization](../advanced/memory-customization.md).

<!--
```java
docs.FileOps.writeResult("docs/core", "memory.md.result", "" + result);
```
-->
