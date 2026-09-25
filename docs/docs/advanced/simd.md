---
sidebar_position: 2
sidebar_label: SIMD
title: SIMD support
---

SIMD support is built into Endive.

All WebAssembly `v128` instructions use the scalar interpreter implementation on every supported
JDK. No extra dependency, JVM flag, or machine-factory configuration is required.

:::warning
SIMD support **REQUIRES** validation. Disabling validation (`WasmModule.builder().withValidation(false)`) is likely to produce incorrect results.
:::

### Migration

The `run.endive:simd` module is gone. If your application declares it and uses
`SimdInterpreterMachine`, remove that dependency and the explicit
`withMachineFactory(SimdInterpreterMachine::new)` call; the default machine from
`run.endive:runtime` now executes `v128` instructions.

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/advanced", "simd.md.result", "empty");
```
-->
