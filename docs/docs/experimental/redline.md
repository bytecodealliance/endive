---
sidebar_position: 2
sidebar_label: Native Compilation (Redline)
title: Native Compilation with Redline
---
## Overview

:::warning[Experimental]
Redline is experimental, see [Why](why.md) for what that means for stability.
It also supports less of the WebAssembly specification than the other execution
modes, so check [Feature support](#feature-support) before adopting it.
:::

Redline compiles your Wasm module to native machine code using [Cranelift](https://cranelift.dev/),
instead of to JVM bytecode. Compilation happens at build time for every supported platform, and the
right one is selected at runtime.

It is a substitute for the [Build Time Compiler](../execution/build-time-compiler.md) only, not for
the interpreter or the [Runtime Compiler](../execution/runtime-compiler.md), and it is enabled on
the same Maven plugin. The bytecode is still generated, and is used at runtime when the
architecture or operating system is not supported.

## Feature support

| Feature | Supported |
|---|---|
| Core specification | ✅ |
| Bulk memory | ✅ |
| Tail call | ✅ |
| Threads and atomics | ✅ |
| Reference type instructions | ✅ |
| Multi memory | ❌ |
| Exception handling | ❌ |
| Garbage collection | ❌ |
| Typed function references | ❌ |
| SIMD | ❌ |
| `externref` values to and from host functions | ❌ |

If your module uses anything unsupported the build fails. There is no per function fallback, so a
single unsupported instruction stops the whole module from compiling.

## Platform support

Native code is generated for six platforms:

| | x86_64 | aarch64 |
|---|---|---|
| **Linux** | ✅ | ✅ |
| **macOS** | ✅ | ✅ |
| **Windows** | ✅ | ✅ |

On any other platform your module still runs, using the compiled bytecode instead. See
[Falling back](#falling-back).

## Usage

Enable it on the compiler plugin:

```xml
<plugin>
  <groupId>run.endive</groupId>
  <artifactId>endive-compiler-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals>
        <goal>compile</goal>
      </goals>
      <configuration>
        <name>org.acme.wasm.MyModule</name>
        <wasmFile>src/main/resources/my.wasm</wasmFile>
        <redlineExperimental>true</redlineExperimental>
      </configuration>
    </execution>
  </executions>
</plugin>
```

and add a runner. This is the only dependency you need, everything else comes transitively:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>redline-runner-experimental</artifactId>
  <version>${endive.version}</version>
</dependency>
```

`redline-runner-experimental` uses the Panama FFM API and requires Java 25 or later. On older
versions use `redline-runner-jffi-experimental`, which needs only Java 11:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>redline-runner-jffi-experimental</artifactId>
  <version>${endive.version}</version>
</dependency>
```

The jffi runner does not bring in [jffi](https://github.com/jnr/jffi) itself, so its version can
follow the rest of your application. Add it next to the runner, together with its native
libraries:

```xml
<dependency>
  <groupId>com.github.jnr</groupId>
  <artifactId>jffi</artifactId>
  <version>${jffi.version}</version>
</dependency>
<dependency>
  <groupId>com.github.jnr</groupId>
  <artifactId>jffi</artifactId>
  <version>${jffi.version}</version>
  <classifier>native</classifier>
</dependency>
```

Without them, `builder()` fails with `NoClassDefFoundError: com/kenai/jffi/MemoryIO` instead of
falling back.

If both runners are present, the Panama runner is used wherever the JDK supports it.

Your module is then used exactly as it would be without redline:

```text
try (var instance = MyModule.builder().build()) {
    var f = instance.export("my_function");
}
```

## Falling back

When native code cannot be used, `builder()` falls back to the bytecode produced by the
[Build Time Compiler](../execution/build-time-compiler.md), which is always generated alongside it.
This happens on platforms outside the table above, or when no runner is on the classpath. A jffi
runner without jffi is an error, not a fallback.

Your module keeps working either way, so the fallback is silent. To check which one you got:

```text
MyModule.nativeProvider().isPresent()
```

It is `true` when native code is in use and `false` when the bytecode is. `MyModule.safeBuilder()`
always uses the bytecode, which is useful for comparing the two.

`nativeProvider()` and `imports()` exist only while redline is enabled. The rest of the generated
module is there either way.

## What to expect

**Jar size.** Native code is larger than the Wasm it comes from, and by default one
copy is generated per platform. If you know where you deploy, list only those targets:

```xml
<redlineTargetsExperimental>
  <target>x86_64-unknown-linux-gnu</target>
</redlineTargetsExperimental>
```

The available triples are `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`,
`x86_64-apple-darwin`, `aarch64-apple-darwin`, `x86_64-pc-windows-msvc` and
`aarch64-pc-windows-msvc`.

**Build time.** Compiling for every platform takes noticeably longer than the build time compiler
alone. Narrowing the target list helps here too.

**Imported memories, tables and globals.** Create anything you pass in through `ImportValues` from
`MyModule.imports()`, not by constructing it yourself. Redline reads these through a raw address, so
it can only use ones it made, and building one yourself fails with a message saying so. Modules that
declare their own memory, including anything built for WASI, are unaffected.

```text
var imports = MyModule.imports();

var memory = imports.memory(new MemoryLimits(1, 2));
var table = imports.table(new Table(ValType.FuncRef, new TableLimits(1)), REF_NULL_VALUE);
var counter = imports.global(Value.i32(0), MutabilityType.Var);

var importValues = ImportValues.builder()
        .addMemory(new ImportMemory("env", "memory", memory))
        .addTable(new ImportTable("env", "table", table))
        .addGlobal(new ImportGlobal("env", "counter", counter))
        .build();

try (var instance = MyModule.builder().withImportValues(importValues).build()) {
    instance.export("my_function").apply();

    // what the module wrote, on whichever backend it ran on
    System.out.println(counter.getValue());
}
```

The factory hands back native instances on a platform in the table above and ordinary ones
everywhere else, so this code is the same either way and needs no branch of its own. That matters
because it is decided at runtime: one jar can run natively on one machine and on bytecode on
another.

`imports()` exists only while redline is enabled. Without it you build these the ordinary way, so
this is the one place where turning redline off means editing code.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/experimental", "redline.md.result", "empty");
```
-->
