# Redline Integration Plan

Redline is a native compiler for WebAssembly that uses Cranelift to compile Wasm functions to machine code (x86_64, aarch64). It currently lives as a standalone project at `chicory-redline`. This plan describes how to rebuild it inside endive as a first-class, experimental module family.

## Architecture Overview

### What redline does (compilation pipeline)

```
Wasm bytecode
    |
    v
NativeCompiler (Java) -- walks Wasm instructions, emits Cranelift IR
    |  via RedlineBridge (Cranelift compiled to Wasm, run by endive itself)
    v
Native machine code (byte[][] -- one blob per Wasm function)
    |
    v
NativeMachine (Java) -- maps code into executable memory, builds trampolines,
                         executes via Panama FFM downcalls (Java 25+)
```

### Current chicory-redline modules

| Module | Purpose |
|--------|---------|
| `api` | `RedlineInstance`, `RedlineMachineFactoryProvider` (SPI), `NativeCodeSerializer`, `RedlineTarget`, `CtxBuffer` |
| `bridge` | `RedlineBridge` -- wraps `cranelift_bridge.wasm` (the Cranelift compiler itself running as Wasm) |
| `compiler` | `NativeCompiler`, `NativeAnalyzer`, `NativeEmitters`, `NativeValueStack`, `EmitContext` |
| `build-time-compiler` | `Generator` -- produces Java sources + serialized native code at build time |
| `compiler-maven-plugin` | Maven mojo wrapping the Generator |
| `runner` | Panama FFM backend (Java 25+): `NativeMachineFactory`, `NativeInstance`, `NativeMachine`, `NativeMemory`, `NativeTable`, `NativeGlobalInstance` |
| `runner-jffi` | JFFI backend (Java 11+): same structure, alternative FFI |
| `redline` | `UniversalInstance` -- smart builder with native-first, bytecode-fallback |

## Design Decisions

### 1. No UniversalInstance

The `UniversalInstance` in chicory-redline is a smart builder that tries native execution first, then falls back to endive's JVM bytecode compiler. This adds an indirection layer that isn't necessary.

**Decision:** Drop `UniversalInstance` entirely. The generated `Module` class from the build-time compiler can directly expose a builder that tries native first and falls back. The fallback logic is a few lines in the generated code -- it doesn't need its own module.

### 2. Instance becomes AutoCloseable

Currently endive's `Instance` (`run.endive.runtime.Instance`) is NOT `AutoCloseable`. The chicory-redline codebase works around this with `RedlineInstance` (an AutoCloseable wrapper) and `RedlineInstanceTracker` (a JUnit extension that tracks instances and closes them in `@AfterAll`).

**Decision:** Make `Instance` implement `AutoCloseable`. The `close()` method delegates to the `Machine` (if the machine is `AutoCloseable`). For the interpreter, `close()` is a no-op. For redline's `NativeMachine`, it releases off-heap resources (Arena, mmap'd code regions, native memory).

This eliminates:
- `RedlineInstance` wrapper
- `RedlineInstanceTracker` JUnit extension
- `NativeInstance` convenience alias
- The entire concept of a "closeable wrapper around Instance"

Tests become:
```java
try (var instance = Instance.builder(module)
        .withMachineFactory(factory::compile)
        .build()) {
    instance.export("func").apply();
}
```

### 3. Cleanup: AutoCloseable only, no Cleaner

The current redline uses `java.lang.ref.Cleaner` as a safety net in `NativeMachine` -- registering a `CleanupAction` that runs if the instance is GC'd without being closed. The `NativeMachineFactory.close()` then calls `cleanable.clean()` explicitly.

**Decision:** Remove the Cleaner safety net. Use only `AutoCloseable` (try-with-resources or explicit `close()`). Rationale:
- Cleaner-based cleanup is unpredictable (GC-dependent timing)
- It masks resource leaks instead of surfacing them
- It adds complexity (CleanupAction record, captured references, races between explicit and GC cleanup)
- The principled approach: if you allocate native resources, you close them. If you forget, you leak (and we can add leak detection in debug builds later)

The cleanup chain becomes:
```
Instance.close()
  -> Machine.close()    (if Machine implements AutoCloseable)
    -> Arena.close()    (frees all Panama allocations, upcall stubs)
    -> munmap()         (code region, trampoline region)
    -> NativeMemory.close()  (the 4GB virtual reservation)
```

### 4. Test codegen changes

The current test generator (`test-gen-lib` / `test-gen-plugin`) generates tests that create `Instance` objects without closing them. For the interpreter this is fine (no native resources), but for redline we need cleanup.

**Decision:** Modify the test codegen templates to wrap Instance creation in try-with-resources where feasible, or add an `@AfterEach` / `@AfterAll` hook that closes tracked instances. Since Instance is now AutoCloseable, this is natural. The linking tests (which create multiple instances across a test class) can use a list + cleanup in `@AfterAll`.

### 5. No SPI -- direct factory

The chicory-redline SPI pattern (`RedlineMachineFactoryProvider` via ServiceLoader) was designed for swapping Panama vs JFFI by classpath. Since we're starting with Panama only and deferring JFFI, the SPI adds unnecessary indirection.

**Decision:** Drop the SPI layer entirely. The runner module exposes `NativeMachineFactory` directly. Add SPI back when JFFI is ported. This eliminates `RedlineMachineFactoryProvider`, `PanamaMachineFactoryProvider`, and the `META-INF/services` registration.

### 6. Integrate into existing Maven plugin

Instead of creating a new `redline-compiler-maven-plugin-experimental`, extend the existing `endive-compiler-maven-plugin` with a redline compilation goal or configuration flag. The build-time compiler library (`redline-build-time-compiler-experimental`) remains a separate module, but the user-facing Maven plugin is a single entry point.

### 7. Module structure and naming

All redline modules live under `redline/` in the endive repo. Maven artifact IDs use `-experimental` suffix. Group ID is `run.endive`. JFFI is deferred.

```
redline/
  api/                  -> run.endive:redline-api-experimental
  bridge/               -> run.endive:redline-bridge-experimental
  compiler/             -> run.endive:redline-compiler-experimental
  build-time-compiler/  -> run.endive:redline-build-time-compiler-experimental
  runner/               -> run.endive:redline-runner-experimental        (Java 25+)
  runner-tests/         -> run.endive:redline-runner-tests-experimental
  jmh/                  -> run.endive:redline-jmh-experimental
```

The existing `compiler-maven-plugin/` module is extended (not duplicated).

Package namespace: `run.endive.redline.*` (mirroring the current `io.roastedroot.redline.*` structure).

### 8. Dependency direction

Redline modules depend on endive core (`runtime`, `wasm`, `compiler`, `codegen`, `wasi`), never the reverse. The core modules have zero knowledge of redline. This is the same clean boundary that exists in chicory-redline today (which depends on chicory as an external dep).

### 9. The Cranelift bridge Wasm module

The `cranelift_bridge.wasm` file is a pre-compiled Cranelift compiler itself, running as a Wasm guest inside endive. The `RedlineBridge` class wraps it using the `@WasmModuleInterface` annotation processor. This pattern stays -- it's elegant and self-hosting.

The bridge wasm file moves to `redline/bridge/src/main/resources/` (or referenced via the templated `WasmResource.java` as it is today).

### 10. Safe mode in MachineFactory

When a module has been AOT-compiled with Cranelift, the MachineFactory must offer a "safe" mode that **bypasses native code entirely and forces endive's JVM bytecode compiler** instead. No architecture detection, no native code loading -- just the proven bytecode path.

This is a runtime switch on the factory/builder, so users can flip between native and safe without recompiling. Useful for debugging ("is this a redline bug or a wasm bug?"), gradual rollout, and fallback.

## Principles

- **No hacks.** Every change must be organic and principled. No `@SuppressWarnings`, no instanceof downcasts where a proper type hierarchy works, no workarounds. If the right fix is to change an interface, change the interface.
- **Incremental.** Each phase produces a working, testable state.
- **Ask if in doubt.** Don't assume -- clarify before committing to a design.

## Implementation Phases

### Phase 1: Make Instance AutoCloseable

**Files to modify:**
- `runtime/src/main/java/run/endive/runtime/Machine.java` -- extend `AutoCloseable` with a default no-op `close()`
- `runtime/src/main/java/run/endive/runtime/Instance.java` -- implement `AutoCloseable`, `close()` delegates to `machine.close()`

**Approach:** `Machine extends AutoCloseable` with `default void close() {}`. This keeps `@FunctionalInterface` valid (one abstract method), the interpreter's close is a no-op by default, and `Instance.close()` is simply `machine.close()` -- no instanceof, no SuppressWarnings.

**Test:** Run the full existing endive test suite (`runtime-tests`, `compiler-tests`, `machine-tests`) to verify no regression. `close()` on interpreter-backed instances must be a no-op.

### Phase 2: Create module skeleton

Set up the Maven module structure under `redline/`:
- Parent POM at `redline/pom.xml` (inherits from endive root)
- Child POMs for each module
- Add `redline` modules to root POM (in a profile, e.g. `redline` or `experimental`)
- Wire up spotless, checkstyle, error-prone configs from parent

### Phase 3: Port the API module

Migrate from `io.roastedroot.redline.api` to `run.endive.redline.api`:
- `RedlineTarget` -- platform detection (host triple, resource suffix) -- keep as-is
- `NativeCodeSerializer` -- serialization of `byte[][]` native code -- keep as-is
- `CtxBuffer` -- native call context struct layout -- keep as-is
- `TypeMapUtils` -- canonical type map builder -- keep as-is
- `Interruptable` -- interrupt request/clear interface -- keep as-is
- **Drop** `RedlineInstance` (replaced by AutoCloseable Instance)
- **Drop** `RedlineMachineFactoryProvider` (no SPI needed)

### Phase 4: Port the bridge module

- Copy `cranelift_bridge.wasm` resource
- Port `RedlineBridge` -- update to use endive's annotation processor (`@WasmModuleInterface`)
- Update the `WasmResource.java` template

### Phase 5: Port the compiler module

Port the core compiler classes:
- `NativeCompiler` -- the orchestrator (walks Wasm instructions, builds Cranelift IR via bridge)
- `NativeAnalyzer` -- pre-pass reachability analysis
- `NativeEmitters` -- instruction emission helpers
- `NativeValueStack` -- scope-aware value stack
- `EmitContext` -- emission state

These are largely self-contained. Main changes:
- Update imports from `com.dylibso.chicory.*` to `run.endive.*`
- Update bridge references to the new package

### Phase 6: Port the runner module (Panama) + spec tests

Port `NativeMachineFactory`, `NativeMachine`, `NativeMemory`, `NativeTable`, `NativeGlobalInstance`, `PanamaExecutor`.

Key changes:
- **Remove Cleaner from NativeMachine** -- just implement `AutoCloseable` directly
- `NativeMachineFactory` used directly (no SPI indirection)
- The factory provides `Machine`, `TableFactory`, `GlobalFactory`, `MemoryFactory` callbacks to `Instance.Builder`
- `NativeMachine` implements `AutoCloseable` and is what `Instance.close()` eventually delegates to

**Tests (immediately after runner compiles):**
- Port `runner-tests` with endive's test-gen infrastructure
- Update `Spectest` / `TestModule` / `NativeInstanceBuilder` helpers to use AutoCloseable Instance directly
- No `RedlineInstanceTracker` -- use try-with-resources / `@AfterAll` cleanup
- Run the full spec test suite to validate the runner end-to-end
- Port hand-written tests: `NativeMemoryLeakTest`, `FactoryReuseTest`, `HostExceptionTest`, `InterruptionTest`, `ShootoutTest`

### Phase 7: Extend the existing Maven plugin + port build-time compiler

- Port `Generator` (as `redline-build-time-compiler-experimental`) -- update generated code to use endive APIs directly (no `RedlineInstance`, no `UniversalInstance`, no SPI)
- Extend `endive-compiler-maven-plugin` with a redline compilation goal/configuration
- Generated code produces `Instance.Builder`-compatible output using `NativeMachineFactory` directly

### Phase 8: Benchmarks and integration tests

- Port JMH benchmarks
- Port integration tests
- Validate performance parity with standalone chicory-redline

## Verification

- `mvn clean install -pl redline -am` builds all redline modules
- Spec tests pass for Panama runner
- No Cleaner/Cleanable references in redline code (grep to verify)
- No `RedlineInstance`, `UniversalInstance`, or `RedlineMachineFactoryProvider` references
- No separate `redline-compiler-maven-plugin` -- redline compilation is a feature of the existing plugin
- Instance.close() is a no-op for interpreter tests (no regression)
- JMH benchmarks show comparable performance to standalone chicory-redline
