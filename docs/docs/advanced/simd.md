---
sidebar_position: 2
sidebar_label: Simd
title: Simd support
---

> **NOTE:** SIMD support is available only for Java 21+ and interpreter mode

If you are using a version of Java that supports [JEP 448 - Vector API](https://openjdk.org/jeps/448) you can leverage [Vector instructions](https://webassembly.github.io/spec/core/syntax/instructions.html#vector-instructions).

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:simd:999-SNAPSHOT

import run.endive.wasm.Parser;
import run.endive.runtime.Instance;

docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "your.wasm");
```
-->

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

```
-->

After adding the dependency:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>simd</artifactId>
</dependency>
```

You can instantiate a module with SIMD support by explicitly providing a `MachineFactory`:

```java
import run.endive.simd.SimdInterpreterMachine;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).withMachineFactory(SimdInterpreterMachine::new).build();
```

> **_NOTE:_**  SIMD support **REQUIRES** validation. Disabling validation  (`WasmModule.builder().withValidation(false)`) is likely to produce incorrect results.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/advanced", "simd.md.result", "empty");
```
-->

