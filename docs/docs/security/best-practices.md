---
sidebar_position: 2
sidebar_label: Best Practices
title: Security Best Practices
---

# Security Best Practices

Practical guidance for running Wasm modules securely with Endive.

## Writing Safe Host Functions

Host functions are the primary attack surface — they cross the sandbox boundary and execute with full JVM privileges.

**Always validate arguments from Wasm:**

```java title="Example"
HostFunction readMemory = (Instance instance, long... args) -> {
    int offset = (int) args[0];
    int length = (int) args[1];

    // Validate bounds BEFORE accessing memory
    var memory = instance.memory();
    if (offset < 0 || length < 0 || Math.addExact(offset, length) > memory.pages() * 65536) {
        throw new TrapException("out of bounds memory access");
    }

    byte[] data = memory.readBytes(offset, length);
    // ... process data safely
    return new long[0];
};
```

**Key rules:**
- Validate all memory offsets and lengths before reading/writing
- Never use Wasm-provided values as array indices without bounds checking; keep overflow in mind or use overflow-safe methods such as `Math#addExact` or `Objects#checkFromIndexSize`
- Be cautious with string decoding — enforce maximum lengths
- Avoid exposing file paths, SQL queries, or shell commands derived from Wasm input

## WASI Sandboxing

:::warning[Security Consideration]
WASI file access does not enforce path sandboxing by default. Passing the host filesystem directly exposes all files the JVM process can access.
:::

**Use a virtual filesystem:**

```java title="Example"
var options = WasiOptions.builder()
        // Use ZeroFs to restrict access to specific directories
        .withDirectory("/guest/data", zeroFs.getPath("/host/sandboxed/data"))
        .withStdout(myStdout)
        .withStderr(myStderr)
        .build();
```

**Never use `inheritSystem()` with untrusted modules** — it grants full host filesystem, environment, and stdio access.

## Resource Limits

Wasm modules can consume unbounded CPU and memory. Always set limits when running untrusted code.

**CPU timeout via thread interruption:**

```java title="Example"
var executor = Executors.newSingleThreadExecutor();
var future = executor.submit(() -> instance.export("run").apply());

try {
    var result = future.get(5, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    future.cancel(true); // interrupts the Wasm execution
}
```

See [CPU Limits](/docs/advanced/cpu-limits) for more patterns.

## Compiler Security

The runtime and build-time compilers translate Wasm to JVM bytecode for performance. When running untrusted modules:

- **Prefer the interpreter** for maximum sandbox assurance — it doesn't generate JVM bytecode
- **Protect compiler cache directories** with restrictive permissions (`chmod 700`) if using the [directory cache](/docs/execution/compiler-cache/#the-directory-cache)
- **Set JVM heap limits** when compiling large untrusted modules to prevent resource exhaustion

## Dependency Supply Chain

- All Endive dependencies are checked against the [Bytecode Alliance allowed license list](/docs/security/overview) in CI
- Dependency vulnerabilities are scanned nightly via OWASP Dependency-Check
- Dependabot is enabled for automated security updates
- All dependency updates are manually reviewed before merging

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT

docs.FileOps.writeResult("docs/security", "best-practices.md.result", "empty");
```
-->
