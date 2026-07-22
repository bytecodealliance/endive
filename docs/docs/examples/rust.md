---
sidebar_position: 1
sidebar_label: Rust
title: Using Rust with Endive
---
## Compile Rust to Wasm

Compiling a Rust library to Wasm is easy and can be performed using standard `rustc` options:

```bash
rustc --target=wasm32-unknown-unknown --crate-type=cdylib
```

when you need to add support for WASI preview 1 (typically when using CLIs) you can use:

```bash
rustc --target=wasm32-wasi --crate-type=bin
```

:::tip
For production usage, make sure to produce an optimized Wasm module by using the standard compiler options `-C opt-level = 3` (speed) or `-C opt-level = "z"` (size).
:::

## Using in Endive

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "count_vowels.rs.wasm");

System.setOut(new PrintStream(
  new BufferedOutputStream(
    new FileOutputStream("docs/examples/rust.md.result"))));
```
-->

```java
import run.endive.wasm.Parser;
import run.endive.runtime.Instance;

var instance = Instance.builder(Parser.parse(new File("count_vowels.rs.wasm"))).build();

var alloc = instance.export("alloc");
var dealloc = instance.export("dealloc");
var countVowels = instance.export("count_vowels");

var memory = instance.memory();
var message = "Hello, World!";
byte[] bytes = message.getBytes();
int len = bytes.length;
int ptr = (int) alloc.apply(len)[0];

memory.write(ptr, bytes);

var result = countVowels.apply(ptr, len)[0];
System.out.println(result);
```
