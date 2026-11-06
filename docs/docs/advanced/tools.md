---
sidebar_position: 3
sidebar_label: Tools
title: Tools
---
Endive publishes a few WebAssembly tools as Maven artifacts, compiled to Wasm and run with Endive itself.

## WebAssembly Binary Toolkit

A few [wabt](https://github.com/WebAssembly/wabt) tools, the ones Endive's own build uses:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>wabt</artifactId>
  <version>${endive.version}</version>
</dependency>
```

## Wasm Tools

A few [wasm-tools](https://github.com/bytecodealliance/wasm-tools) binaries, which track the latest Wasm proposals:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>wasm-tools</artifactId>
  <version>${endive.version}</version>
</dependency>
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:wabt:999-SNAPSHOT
//DEPS run.endive:wasm-tools:999-SNAPSHOT

import run.endive.wasm.Parser;
import run.endive.runtime.Instance;

System.setOut(new PrintStream(
  new BufferedOutputStream(
    new FileOutputStream("docs/advanced/tools.md.result"))));
```
-->

## wat2wasm

Endive does not parse the Wasm text format. Use `wat2wasm` to convert text to binary:

```java
import run.endive.wabt.Wat2Wasm;
// or
import run.endive.tools.wasm.Wat2Wasm;

var wasm = Wat2Wasm.parse(
    "(module (func (export \"add\") (param $x i32) (param $y i32) (result i32)"
    + " (i32.add (local.get $x) (local.get $y))))");

var moduleInstance = Instance.builder(Parser.parse(wasm)).build();

var addFunction = moduleInstance.export("add");
var result = addFunction.apply(1, 41)[0];

System.out.println(result);
```
