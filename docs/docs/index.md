---
sidebar_position: 1
sidebar_label: Quick Start
title: Quick start
---

:::info[Requirements]
Endive requires **Java 11** or later. SIMD instructions are supported on all supported Java versions.
:::

### Install the dependency

[![Maven Central](https://img.shields.io/maven-central/v/run.endive/runtime)](https://central.sonatype.com/artifact/run.endive/runtime)

To use the runtime, you need to add the `run.endive:runtime` dependency
to your dependency management system.

#### Maven

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>runtime</artifactId>
  <version>latest-release</version>
</dependency>
```

#### Gradle

```groovy
implementation 'run.endive:runtime:latest-release'
```


<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT
```
-->

### Loading and Instantiating Wasm Modules

First your Wasm module must be loaded from disk and then instantiated. Let's [download a test module](https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/iterfact.wat.wasm) .
This module contains some code to compute factorial:

Download from the link or with curl:

```bash
curl https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/iterfact.wat.wasm > factorial.wasm
```

<!--
```java
docs.FileOps.copyFromWasmCorpus("iterfact.wat.wasm", "factorial.wasm");
```
-->

Load this module and instantiate it:

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

:::note[Threading]
`Instance` and `Store` are not thread-safe. Create separate instances per thread, or synchronize access externally. Memory operations (used by the Wasm threads proposal) are thread-safe.
:::

You can think of the `module` as of inert code, and the `instance` 
is the run-time representation of that code: a virtual machine ready to execute.

### Invoking a Wasm Function

Wasm modules, like all code modules, can export functions to the outside
world. This module exports a function called `"iterFact"`. 
We can get a handle to this function using `Instance#export(String)`:

```java
ExportFunction iterFact = instance.export("iterFact");
```

`iterFact` can be invoked with the `apply()` method. We must map any Java types to raw `long`s and do the reverse
when we want to go back to Java.

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
Functions in Wasm can return multiple values, hence the array. This function only returns one value, so we take the first value.
:::
