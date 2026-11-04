---
sidebar_position: 1
sidebar_label: Runtime Compilation
title: Runtime Compilation
---
## Overview

:::warning[Security Consideration]
The compiler translates Wasm to JVM bytecode without post-compilation verification. When compiling untrusted modules, prefer the interpreter for higher assurance, or run compiled code in an isolated classloader. See [Security Model](/docs/security/overview).
:::

:::info[Resource Limits]
Compiling very large Wasm modules can consume significant memory and CPU. Consider setting JVM heap limits and compilation timeouts when processing untrusted input.
:::

The runtime compiler translates Wasm functions to JVM bytecode in memory when the instance is created, as a drop-in replacement for the interpreter.
Compiled code runs much faster and uses less memory than interpreted code, at the cost of a small delay at instantiation.
Use the [build-time compiler](./build-time-compiler.md) to move that work into your build.

Modules that use SIMD (`v128`) instructions cannot be compiled and must run in the interpreter.

## Using

### Required Maven Changes

Add the following dependency:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>compiler</artifactId>
</dependency>
```

### Code Changes

You enable the runtime compiler by configuring the instance to use `MachineFactoryCompiler::compile` as the machine factory instead 
of the default `InterpreterMachine`.

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
import run.endive.wasm.WasmModule;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).
        withMachineFactory(MachineFactoryCompiler::compile).
        build();
```

### Interpreter Fall Back

The WASM to bytecode compiler translates each WASM function into JVM method.  Occasionally you will find WASM module where functions are bigger than the maximum method size allowed by the JVM. In these rare cases, we fall back to executing large functions in the interpreter.  

Since interpreted functions have worse performance, we want to make sure you are aware this is happening so the runtime compiler will log messages to standard error like: 

```text
Warning: using interpreted mode for WASM function index: 232
```

By default, the compiler uses `InterpreterFallback.WARN` behavior, which logs warning messages when falling back to the interpreter. If you are happy with these methods being interpreted, you can configure the compiler with `InterpreterFallback.SILENT` to silence those messages:

```java
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.InterpreterFallback;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).
        withMachineFactory(
                MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .compile()
        ).
        build();
```

If you want to ensure the functions are never interpreted, you can modify the above to use `InterpreterFallback.FAIL` instead. This will throw an exception if any function is too large to compile.

An even better way to silence the use of interpreted functions (this will speed up your compile times) is to explicitly list the function indexes that should be interpreted:

```java
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.InterpreterFallback;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import java.io.File;
import java.util.Set;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).
        withMachineFactory(
                MachineFactoryCompiler.builder(module)
                .withInterpretedFunctions(Set.of(232, 251))
                .compile()
        ).
        build();
```

Typically, you can obtain the list of the functions by running the compiler once with `InterpreterFallback.WARN`

### Releasing Function Bodies

Once compiled, the parsed function bodies are only needed by functions that run in the interpreter.
`compileModule()` returns the machine factory together with a copy of the module that keeps only
those bodies and no custom sections, so the rest can be garbage collected once the original module
is no longer referenced:

```java
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import java.io.File;

var compiled = MachineFactoryCompiler.builder(Parser.parse(new File("your.wasm"))).compileModule();
var instance = Instance.builder(compiled).build();
```

### Method Names

By default, the compiler names compiled methods `func_0`, `func_1`, etc. A `MethodPrefixer` lets you
replace the `func` prefix with something more recognisable, which improves readability of thread
dumps, profiler output and stack traces. `MethodPrefixer.fromNameSection()` uses the function names
from the module's name section, where present:

```java
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.MethodPrefixer;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import java.io.File;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).
        withMachineFactory(
                MachineFactoryCompiler.builder(module)
                .withMethodPrefixer(MethodPrefixer.fromNameSection())
                .compile()
        ).
        build();
```

A prefixer supplies only the prefix; the compiler always appends `_<funcId>` to produce the method
name (e.g. `my_func_42`). That keeps method names unique whatever the prefixer returns, and keeps the
WASM function index recoverable from any method name.

Characters that are illegal in JVM method names (`. ; [ / < >`) are replaced with underscores. A
prefixer that needs to preserve names exactly can avoid the substitution by encoding those
characters itself, for example by percent-encoding them.

The prefix is a hint for humans inspecting a compiled method name. Tools should not parse it, and
should use the function index instead.

### Method Splitting (experimental)

The JVM does not JIT-compile methods larger than 8000 bytes of bytecode, so a large WASM function
compiled to a single method always runs in the JVM's bytecode interpreter, however hot it is. With
method splitting enabled, the compiler splits such functions into several smaller methods that the
JIT can compile:

```java
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import java.io.File;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).
        withMachineFactory(
                MachineFactoryCompiler.builder(module)
                .withMethodSplitting(true)
                .compile()
        ).
        build();
```

Functions that cannot be split safely are compiled as before. The compiler prints a line for each
function it splits unless `InterpreterFallback.SILENT` is set.

### Caveats 

Please note that compiling and executing Wasm modules at runtime requires:
- an external dependency on [ASM](https://asm.ow2.io/)
- the usage of runtime reflection

This is usually fine when running on a standard JVM, but it involves some additional configuration when using tools like `native-image`.

<!--
```java
docs.FileOps.writeResult("docs/execution", "runtime-compiler.md.result", "empty");
```
-->
