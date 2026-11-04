---
sidebar_position: 3
sidebar_label: Runtime Compiler Cache
title: Runtime Compiler Cache
---

The compiler cache stores the bytecode produced by the [runtime compiler](runtime-compiler.md), so later runs skip compilation and start faster.

Use the experimental directory cache below, or implement the `run.endive.compiler.Cache` interface:

```java
public interface Cache {
    byte[] get(String key) throws IOException;
    void putIfAbsent(String key, byte[] data) throws IOException;
}
```

The compiler uses the module digest (default SHA-256) as the cache key. For example, `"sha-256:KRgyTkCm43c34ksqtA8gmdDw4YCfquC2G0qfIFCpb+w="` could be a key. The cached value is a JAR containing the module's compiled bytecode.

## The directory cache

:::warning[Security Consideration]
The cache stores bytecode on disk without integrity checks. Keep the directory private (`chmod 700`) and never share it across trust boundaries.
:::

`DirectoryCache` stores one jar per module under a directory of your choice. Writes are atomic, so several processes can share a cache. Nothing is ever evicted: delete files to free disk space.

Add the dependency:

```xml
<dependency>
  <groupId>run.endive</groupId>
  <artifactId>dircache-experimental</artifactId>
</dependency>
```

Create the cache:

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:compiler:999-SNAPSHOT
//DEPS run.endive:dircache-experimental:999-SNAPSHOT
```
-->
```java
import java.nio.file.Path;
import run.endive.experimental.dircache.DirectoryCache;

var cache = new DirectoryCache(Path.of("cache"));
```

Configure the compiler to use the cache via `MachineFactoryCompiler.builder(...)`:

<!--
```java
import java.io.File;
import java.nio.file.Files;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.runtime.Instance;
docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "your.wasm");

var cache = new DirectoryCache(Files.createTempDirectory("cache"));
```
-->

```java
var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module).
        withMachineFactory(
            MachineFactoryCompiler.builder(module).withCache(cache).compile()
        ).
        build();
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
docs.FileOps.writeResult("docs/execution", "compiler-cache.md.result", "empty");
```
-->
