---
sidebar_position: 1
sidebar_label: Runtime Compiler
title: Runtime Compiler
---

The runtime compiler translates Wasm functions to JVM bytecode in memory when the instance is created, as a drop-in replacement for the interpreter.
Compiled code runs much faster and uses less memory than interpreted code, at the cost of a small delay at instantiation.
Use the [build-time compiler](./build-time-compiler.md) to move that work into your build.

:::warning[Security Consideration]
The compiler generates JVM bytecode without a separate verification pass. Prefer the interpreter for untrusted modules, and bound heap and time when compiling untrusted input. See [Security Model](/docs/security/overview).
:::

Modules that use SIMD (`v128`) instructions cannot be compiled and must run in the interpreter.

## Usage

Add the dependency:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>compiler</artifactId>
</dependency>
```

Then configure the instance to use `MachineFactoryCompiler::compile` as its machine factory:

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:compiler:999-SNAPSHOT

import run.endive.wasm.Parser;
import run.endive.runtime.Instance;
docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "your.wasm");
```
-->

```java
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.wasm.Parser;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).
        withMachineFactory(MachineFactoryCompiler::compile).
        build();
```

The runtime compiler depends on [ASM](https://asm.ow2.io/) and uses reflection, which may need extra configuration with tools like `native-image`.

## Options

`MachineFactoryCompiler.builder(module)` exposes the compiler options. All of them are optional:

```java
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.MethodPrefixer;
import java.util.Set;

var instance = Instance.builder(module).
        withMachineFactory(
                MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .withMethodPrefixer(MethodPrefixer.fromNameSection())
                .compile()
        ).
        build();
```

### Functions too large to compile

A Wasm function that exceeds the JVM method size limit runs in the interpreter instead, and the compiler prints a warning naming its function index.
`withInterpreterFallback(InterpreterFallback.SILENT)` drops the warning; `InterpreterFallback.FAIL` turns it into an exception.
To skip compiling functions you already know are too large, list them with `withInterpretedFunctions(Set.of(232, 251))`; any other function that turns out too large then fails.

### Method names

Compiled methods are named `func_0`, `func_1`, and so on.
`withMethodPrefixer(MethodPrefixer.fromNameSection())` names them after the module's name section instead, so stack traces and profiler output show the Wasm function names.

### Method splitting (experimental)

The JVM does not JIT-compile methods larger than 8000 bytes of bytecode, so a large Wasm function compiled to a single method stays in the JVM's bytecode interpreter however hot it is.
`withMethodSplitting(true)` splits such functions into several smaller methods that the JIT can compile.
Functions that cannot be split safely are compiled as before. The compiler prints a line for each function it splits unless `InterpreterFallback.SILENT` is set.

### Releasing function bodies

After compilation the parsed function bodies are only needed by the interpreter.
To let the garbage collector reclaim them and keep the instance's footprint small, build the instance from `compileModule()` instead of `compile()`:

```java
var compiled = MachineFactoryCompiler.builder(Parser.parse(new File("your.wasm"))).compileModule();
var instance = Instance.builder(compiled).build();
```

<!--
```java
docs.FileOps.writeResult("docs/execution", "runtime-compiler.md.result", "empty");
```
-->
