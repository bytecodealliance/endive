---
sidebar_position: 0
sidebar_label: Overview
title: Compilation
---

Endive can run a Wasm module in three ways: interpreted, compiled to JVM bytecode when the instance is created, or compiled to JVM bytecode at build time.

| Mode | Performance | Dynamic module loading | Requirements | Use when |
|---|---|---|---|---|
| **Interpreter** | 🐢 Slow | ✅ | None | The default. Maximum portability, and the only mode that runs SIMD (`v128`) modules. |
| **[Runtime compiler](runtime-compiler.md)** | 🐇 Fast | ✅ | ASM dependency and reflection | Modules are loaded dynamically and reflection is acceptable. |
| **[Build-time compiler](build-time-compiler.md)** | 🐇 Fast | ❌ | Maven or Gradle plugin | Modules are known at build time. Best startup, no extra runtime dependency, works with `native-image`. |

The interpreter needs nothing beyond `run.endive:runtime`.
Both compilers translate each Wasm function to a JVM method and can leave functions too large to compile to the interpreter.
Neither supports SIMD yet, and SIMD execution relies on validation, so keep it enabled for modules that use `v128`.

[Redline](../experimental/redline.md) is an experimental fourth option that compiles the module to native code at build time.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/execution", "index.md.result", "empty");
```
-->
