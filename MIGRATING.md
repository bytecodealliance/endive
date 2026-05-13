# Migrating from Chicory to Endive

Endive is a fork of [Chicory](https://github.com/dylibso/chicory) by Dylibso, Inc.
This guide documents all breaking changes for users migrating from Chicory.

## Maven Coordinates

| Chicory | Endive |
|---------|--------|
| `com.dylibso.chicory:runtime` | `run.endive:runtime` |
| `com.dylibso.chicory:compiler` | `run.endive:compiler` |
| `com.dylibso.chicory:wasm` | `run.endive:wasm` |
| `com.dylibso.chicory:wasi` | `run.endive:wasi` |
| `com.dylibso.chicory:annotations` | `run.endive:annotations` |
| `com.dylibso.chicory:bom` | `run.endive:bom` |

All module artifact names (`runtime`, `compiler`, `wasm`, etc.) are unchanged.

## Package Names

All packages have moved from `com.dylibso.chicory` to `run.endive`:

```
com.dylibso.chicory.runtime   ->  run.endive.runtime
com.dylibso.chicory.compiler  ->  run.endive.compiler
com.dylibso.chicory.wasm      ->  run.endive.wasm
com.dylibso.chicory.wasi      ->  run.endive.wasi
```

A global find-and-replace of `com.dylibso.chicory` to `run.endive` in your imports covers this.

## Exception Classes

The base exception and interruption exception have been renamed to better reflect their semantics:

| Chicory | Endive | Rationale |
|---------|--------|-----------|
| `ChicoryException` | `WasmEngineException` | Base for engine errors, distinct from `WasmException` (Wasm-level tagged exceptions from the exception-handling proposal) |
| `ChicoryInterruptedException` | `WasmInterruptedException` | Host-initiated interruption of Wasm execution |

All spec-aligned exception names are unchanged: `TrapException`, `InvalidException`, `MalformedException`, `UnlinkableException`, `UninstantiableException`.

## Maven Plugin

| Chicory | Endive |
|---------|--------|
| `com.dylibso.chicory:chicory-compiler-maven-plugin` | `run.endive:endive-compiler-maven-plugin` |
| Goal prefix: `chicory:compile` | Goal prefix: `endive:compile` |
| Default output: `generated-sources/chicory-compiler` | Default output: `generated-sources/endive-compiler` |

## System Properties

| Chicory | Endive |
|---------|--------|
| `chicory.hugeMethodLimit` | `endive.hugeMethodLimit` |
| `chicory.memCopyWorkaround` | `endive.memCopyWorkaround` |
| `chicory.compiler.printUseOfInterpretedFunctions` | `endive.compiler.printUseOfInterpretedFunctions` |

## CLI Binaries

| Chicory | Endive |
|---------|--------|
| `chicory` | `endive` |
| `chicory-compiler` | `endive-compiler` |

## Logger Name

The JUL/System logger name has changed from `"chicory"` to `"endive"`.
If you configure logging levels for the runtime, update your logging configuration accordingly.
