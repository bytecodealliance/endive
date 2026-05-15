---
sidebar_position: 1
sidebar_label: Security Model
title: Security Model
---

# Security Model

Endive executes WebAssembly modules inside the JVM. Understanding the security boundaries is essential when running untrusted code.

## The Wasm Sandbox

WebAssembly provides a sandboxed execution environment by design:

- **Memory isolation**: Each Wasm module operates on its own linear memory. It cannot access the JVM heap, other modules' memory, or host memory directly.
- **No ambient capabilities**: Wasm modules have no access to the filesystem, network, environment variables, or system calls unless the host explicitly provides them via imports.
- **Control flow integrity**: Indirect calls are checked against a type table. Modules cannot jump to arbitrary code.
- **Deterministic execution**: The core Wasm spec produces deterministic results (with exceptions for floating-point NaN bit patterns and threading).

## Trust Boundaries

```
+------------------------------------------------+
|              JVM Host Process                  |
|                                                |
|  +----------------+   +----------------+       |
|  | Host Function A|   | Host Function B|       |
|  +-------+--------+   +-------+--------+       |
|          |                     |               |
|  - - - - | - trust boundary  - | - - - - - -  |
|          |                     |               |
|  +-------v---------------------v--------+      |
|  |          Endive Runtime              |      |
|  |                                      |      |
|  |   +----------+    +----------+       |      |
|  |   | Wasm     |    | Wasm     |       |      |
|  |   | Module A |    | Module B |       |      |
|  |   +----------+    +----------+       |      |
|  +--------------------------------------+      |
+------------------------------------------------+
```

The critical trust boundary is between **Wasm guest code** and **host functions**. Host functions have full JVM privileges. Any argument passed from Wasm to a host function must be validated before use.

## What the Sandbox Does NOT Guarantee

- **CPU limits**: Wasm modules can execute infinite loops. The host must enforce timeouts (see [CPU Limits](/docs/advanced/cpu-limits)).
- **Memory growth limits**: Modules can request memory growth up to the declared maximum. The host should set appropriate limits.
- **Post-compilation verification**: The build-time and runtime compilers translate Wasm to JVM bytecode without a separate verification pass. For maximum assurance with untrusted code, prefer the interpreter.
- **Cache integrity**: The directory-based compiler cache does not verify bytecode integrity on load. Protect cache directories with restrictive permissions.

## WASI and Capability-Based Security

When using WASI, the host controls what capabilities the guest receives:

- **Filesystem access** is opt-in. Use a virtual filesystem (e.g., ZeroFS) to restrict access to specific directories.
- **Environment variables** and **command-line arguments** are explicitly passed by the host.
- **Standard I/O** streams are host-controlled.

See [Best Practices](/docs/security/best-practices) for actionable guidance on securing your Endive deployment.
