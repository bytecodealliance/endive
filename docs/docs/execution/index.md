---
sidebar_position: 0
sidebar_label: Overview
title: Compilation
---

Endive can run a Wasm module in three ways: interpreted, compiled to JVM bytecode when the instance is created, or compiled to JVM bytecode at build time.

| Mode | Performance | Dynamic module loading | Requirements | Use when |
|---|---|---|---|---|
| **Interpreter** | 🐢 Slow | ✅ | None | The default. Maximum portability. |
| **[Runtime compiler](runtime-compiler.md)** | 🐇 Fast | ✅ | ASM dependency and reflection | Modules are loaded dynamically and reflection is acceptable. |
| **[Build-time compiler](build-time-compiler.md)** | 🐇 Fast | ❌ | Maven or Gradle plugin | Modules are known at build time. Best startup, no extra runtime dependency, works with `native-image`. |

The interpreter needs nothing beyond `run.endive:runtime`.
Both compilers translate each Wasm function to a JVM method and can leave functions too large to compile to the interpreter.
SIMD support relies on validation in every mode, so do not disable it for modules that use `v128`.

[Redline](../experimental/redline.md) is an experimental fourth option that compiles the module to native code at build time.

## Feature support

The compilers pass the same specification test suite as the interpreter.

| Proposal | Interpreter | Runtime and build-time compilers | Redline |
|---|---|---|---|
| Core specification | ✅ | ✅ | ✅ |
| Bulk memory and reference types | ✅ | ✅ | ✅ |
| Tail calls | ✅ | ✅ | ✅ |
| Threads and atomics | ✅ | ✅ | ✅ |
| Typed function references | ✅ | ✅ | ❌ |
| Garbage collection | ✅ | ✅ | ❌ |
| Exception handling | ✅ | ✅ | ❌ |
| Multi-memory | ✅ | ✅ | ❌ |
| SIMD | ✅ | ✅ | ❌ |

GC types cross the host boundary as Java objects, see [GC Types](../core/gc-types.md). [WASI Preview 1](../wasi/index.md) is a separate module and works with every mode.

## SIMD

The compilers keep each `v128` value as two 64-bit `long`s and run each SIMD instruction as plain Java code on the two halves.
They don't use the JDK Vector API, so the compiled module still runs on Java 11.

Because of that, a module built with SIMD is not always faster than the same source built without it:

- **Often faster with SIMD:** code where the toolchain auto-vectorized byte loops, such as `memcpy`-like copies, XOR over a buffer, or byte scanning. These touch memory 16 bytes at a time instead of one.
- **Can be about 2x slower with SIMD:** code written with SIMD intrinsics on 32-bit or floating-point lanes throughout, such as hand-vectorized crypto rounds or float math. Without SIMD, the JVM compiles the same scalar code to native instructions directly.

**Before you ship a SIMD build, compile the module both with and without SIMD, and measure both on your own workload.**
Keep whichever is faster; both run in every mode.

| Toolchain | With SIMD | Without SIMD |
|---|---|---|
| clang, wasi-sdk, Emscripten | `-msimd128` | omit `-msimd128` |
| Rust | `-C target-feature=+simd128` | omit `+simd128` |

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/execution", "index.md.result", "empty");
```
-->
