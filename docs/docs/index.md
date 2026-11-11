---
sidebar_position: 1
sidebar_label: Quick Start
title: Quick Start
---

This page goes from an empty project to calling a function exported by a Wasm module.

:::info[Requirements]
Endive requires **Java 11** or later.
:::

## Install

Add the runtime to your project:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>runtime</artifactId>
  <version>${endive.version}</version>
</dependency>
```

See [Installation](getting-started/installation.md) for Gradle and the BOM.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT
```
-->

## Load and instantiate a module

[Download a test module](https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/iterfact.wat.wasm) that computes a factorial, from the link or with curl:

```bash
curl https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/iterfact.wat.wasm > factorial.wasm
```

<!--
```java
docs.FileOps.copyFromWasmCorpus("iterfact.wat.wasm", "factorial.wasm");
```
-->

Parse it and create an instance:

```java
import run.endive.runtime.ExportFunction;
import run.endive.wasm.types.Value;
import run.endive.wasm.Parser;
import run.endive.runtime.Instance;
import java.io.File;

// point this to your path on disk
var module = Parser.parse(new File("./factorial.wasm"));
Instance instance = Instance.builder(module).build();
```

The module is inert code; the instance is a virtual machine ready to execute it.

:::note[Threading]
`Instance` and `Store` are not thread-safe. Create separate instances per thread, or synchronize access externally. Memory operations (used by the Wasm threads proposal) are thread-safe.
:::

## Call a function

The module exports a function called `iterFact`. Get a handle to it with `Instance#export(String)`:

```java
ExportFunction iterFact = instance.export("iterFact");
```

Call it with `apply()`. Arguments and results are raw `long`s, so map Java types yourself on both sides:

```java
var result = iterFact.apply(5)[0];
System.out.println("Result: " + result); // should print 120 (5!)
```

<!--
```java
docs.FileOps.writeResult("docs", "index.md.result", "" + result);
```
-->

:::note
Wasm functions can return multiple values, hence the array. This one returns a single value, so we take the first.
:::
