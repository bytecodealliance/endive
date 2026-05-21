---
sidebar_position: 3
sidebar_label: Security Audit Response
title: Security Audit Response
---

# Security Audit Response

This document responds to each finding from the 2026 security audit of the Endive project.

## Module Stability Context

Endive modules have different stability levels, which affects how findings are prioritized:

- **Stable**: `runtime`, `wasm` (parser/types), `wasi`, `compiler`, `annotations` — full API stability and security guarantees.
- **Experimental**: `dircache-experimental`, `cli-experimental`, `build-time-compiler-cli-experimental` — APIs may change, security hardening is ongoing, documented caveats apply.

## Critical Findings

### C1. Dircache loads unverified bytecode — arbitrary JVM RCE

**Status: Accepted risk (experimental module)**

The `dircache-experimental` module is explicitly marked experimental. Our [Security Model](/docs/security/overview) documentation states:

> The directory-based compiler cache does not verify bytecode integrity on load. Protect cache directories with restrictive permissions.

The cache directory must be access-controlled (`chmod 700` or equivalent). We will consider adding HMAC verification and class-name prefix restrictions as the module matures toward stable status.

### C2. WASI path containment bypassed via symlinks

**Status: Fixed**

Fixed in commit `71d2547b`. We now resolve symlinks component-by-component and validate the final resolved path is within the preopened directory (returns `EACCES` on escape attempts). Regression test added in `916ccce6`.

### C3. pathSymlink allows planting outbound symlinks

**Status: By design (mitigated by C2 fix)**

Per the WASI specification, symlink creation does not validate that targets are within the sandbox. This matches the behavior of the reference implementation (`wasi-common`). The fix in C2 ensures that *following* a planted symlink cannot escape the sandbox — any attempt returns `EACCES`.

### C4. fdRenumber can clobber stdin/stdout/stderr or preopen slots

**Status: Acknowledged — fix forthcoming**

We will add type guards to prevent renumbering over file descriptors 0/1/2 (stdin/stdout/stderr) and preopen slots.

### C5. Parser section-size underflow

**Status: Fixed**

`readVarUInt32` returns an unsigned `long` (validated in range 0 to 2^32-1). Conversion to `int` uses `Math.toIntExact()`, which throws `ArithmeticException` on overflow. The bounds check `buffer.capacity() < sectionLimit` catches remaining edge cases.

## High Findings

### H1. Effective-address arithmetic is int + int

**Status: Fixed**

Fixed in commit `5c8e59ad`. Bounds checking now uses long arithmetic: `(long) addr + (long) size > (long) limit` to prevent signed int overflow. `RUNTIME_MAX_PAGES = 32767` remains as an additional safeguard.

### H2. Cycles in declared supertypes blow the JVM stack

**Status: Fixed**

The validator enforces `MAX_SUBTYPE_DEPTH = 63` and detects supertype cycles through index validation during module loading. Cyclic type declarations are rejected before instantiation.

### H3. call_indirect uses subtyping where the spec requires equality

**Status: Not a bug — correct per GC proposal**

The WebAssembly GC proposal (which Endive implements) changed `call_indirect` from requiring type equality to using type matching (subtyping). This is verified by the official spec test suite (`type-rec.wast`, lines 99–115), which tests structurally equivalent types across `rec` groups passing `call_indirect`. Our `heapTypeSubtype` implementation correctly follows the current specification.

### H4. STRUCT_GET/SET trusts the static typeIdx

**Status: Mitigated — defense-in-depth tracked**

The prerequisite for this attack — supertype cycles (H2) — is now rejected at validation time. We will consider adding a runtime `typeIdx` assertion as defense-in-depth.

### H5. No host-callback recursion limit

**Status: Acknowledged — fix forthcoming**

We will add a call-depth counter at the host-call boundary and catch `StackOverflowError` around host function invocations, preventing unbounded mutual recursion between guest code and host callbacks.

### H6. Only WasmException is caught from host calls

**Status: Acknowledged — fix forthcoming**

We will broaden exception handling to catch `RuntimeException` from host function calls and ensure operand stack consistency on failure. Host implementation details will not leak to guest code.

## Medium Findings

### M1. Cross-preopen pathLink/pathRename not validated

**Status: Acknowledged — tracked for future release**

Per-preopen permission modes (read-only vs. writable) would require a larger design change to the `WasiOptions.Builder` API. We will track this for a future release.

### M2. pathCreateDirectory via attacker-planted parent symlink

**Status: Covered by C2 fix**

All path operations now resolve symlinks and validate containment within the preopened directory. The fix in C2 applies to `pathCreateDirectory` as well.

### M3. Non-atomic data-segment init on instantiation

**Status: Not an issue**

Data segments are initialized during module instantiation before any guest code executes. There is no concurrent access at that point, so atomicity is guaranteed by construction.

### M4. Validator/runtime vector counts are not bounded

**Status: Fixed**

All vector count limits are defined in `WasmLimits` and enforced during parsing. This includes types, functions, globals, data segments, table entries, struct fields, function parameters, and more.

### M5. Custom-section size calculation can underflow

**Status: Fixed**

We explicitly check `size < 0` and throw `MalformedException("unexpected end")`. The int conversion uses `Math.toIntExact()` for safe narrowing.

### M6. Host bridge has no return-value or ref-type validation

**Status: Acknowledged — tracked**

We will add return-value length and type validation as defense-in-depth for the host bridge.

## Lower / Informational Findings

| Finding | Status |
|---------|--------|
| `pathReadlink` returns only `getFileName()` | Conformance bug — will fix |
| `fdReaddir` emits `..` for preopens (leaks parent inode) | Will suppress `..` entry for preopens |
| `Encoding.isValidIdentifier` accepts surrogate halves | Will switch to strict UTF-8 validation per Wasm spec |
| LEB128 decoder accepts non-canonical encodings | Will enforce unused-bits-zero on final byte |
| Multi-byte opcode reconstruction discards bits | Covered by `OpCode.byOpCode` rejection; will add explicit validation |
| `pathOpen` does not check embedded NUL | Linux JDK rejects via `InvalidPathException`; will verify Windows behavior |
