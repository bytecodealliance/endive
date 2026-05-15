---
sidebar_position: 4
sidebar_label: Execution modes
title: Execution modes
---

## Overview

| Mode | Performance | Dynamic Module Loading | Requirements | Output Format | Ideal Use Case |
|---|---|---|---|---|---|
| **Interpreter** | 🐢 Slow | ✅ Supported | None | None - fully interpreted | Default mode; highly portable; suitable for development and environments requiring dynamic loading. |
| **Runtime Compilation** | 🐇 Fast | ✅ Supported | Requires reflection and ASM dependency | In-memory Java Bytecode | Enhanced performance; suitable when dynamic loading is needed and the usage of reflection is fine. |
| **Build time Compilation** | 🐇 Fast | ❌ Not Supported | Build-time tools(e.g., Maven or Gradle plugins) | Plain Java Bytecode | Optimal performance; no dynamic loading; ideal for production with static modules. |

## Summary

- **Interpreter**: Executes WebAssembly (Wasm) modules directly without prior compilation. It's the default mode in Endive, offering maximum portability and simplicity. However, it has slower execution speed compared to compiled modes.

- **Runtime Compilation**: Compiles Wasm modules to Java bytecode at runtime for fast execution. This mode requires one additional dependency on [ASM](https://asm.ow2.io/), it uses reflection, and it loads bytecode dynamically. It fully supports loading new Wasm modules on-the-fly, but it might not be supported on some platforms (such as Android, or GraalVM's native-image). 

- **Build time Compilation**: Compiles Wasm modules to Java bytecode during the build process using tools like Maven or Gradle plugins. This mode offers the best performance and eliminates the need for dynamic loading and additional dependencies, making it ideal for production environments with static modules.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/core", "execution-modes.md.result", "empty");
```
-->
