---
sidebar_position: 2
sidebar_label: SIMD
title: SIMD support
---

SIMD (`v128`) instructions are supported by the interpreter on every supported JDK, with no extra dependency or configuration.

The runtime and build-time compilers do not support SIMD: compiling a module that uses `v128` instructions fails, so such modules must run in the interpreter.

:::warning
SIMD execution relies on validation. Keep it enabled (the default) for modules that use `v128`.
:::

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/advanced", "simd.md.result", "empty");
```
-->
