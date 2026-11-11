---
sidebar_position: 5
sidebar_label: GC Types
title: GC Types
---

Modules built with the WebAssembly garbage collection proposal exchange structs, arrays and `externref` values with the host as ordinary Java objects, which the JVM garbage collector manages like any other.

Download a module that defines a `point` struct:

```bash
curl https://raw.githubusercontent.com/bytecodealliance/endive/main/wasm-corpus/src/main/resources/compiled/gc.wat.wasm > gc.wasm
```

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

docs.FileOps.copyFromWasmCorpus("gc.wat.wasm", "gc.wasm");
```
-->

## Calling exports that use references

Use `applyWithRefs` instead of `apply` for an export whose signature contains a reference type; `apply` throws on those.
The first array has one slot per parameter and carries the numeric arguments; the second carries the reference arguments, in order:

```java
import run.endive.runtime.Instance;
import run.endive.runtime.WasmStruct;
import run.endive.wasm.Parser;
import java.io.File;

var instance = Instance.builder(Parser.parse(new File("./gc.wasm"))).build();

// (param i32 i32) (result (ref $point))
var point = (WasmStruct) instance.export("new_point").applyWithRefs(new long[] {3, 4}, null).refResult(0);

// (param (ref null $point)) (result i32)
var sum = instance.export("sum").applyWithRefs(new long[] {0}, new Object[] {point}).longResult(0);
assert(7L == sum);
```

The returned `CallResult` exposes numeric results through `longResult(i)` and reference results through `refResult(i)`.

## Reading and building structs and arrays

`WasmStruct` exposes its fields by index: `field(i)` for numeric fields and `fieldRef(i)` for reference fields.
`WasmArray` does the same with `get(i)` and `getRef(i)`.
To create one on the host, use the builder and add the fields in declaration order:

```java
assert(3L == point.field(0));

var origin = WasmStruct.builder().
        typeIdx(point.typeIdx()).
        addField(0).
        addField(0).
        build();
assert(0L == instance.export("sum").applyWithRefs(new long[] {0}, new Object[] {origin}).longResult(0));
```

<!--
```java
docs.FileOps.writeResult("docs/core", "gc-types.md.result", "" + sum);
```
-->

## Host functions receiving references

A host function whose signature contains reference types implements `applyWithRefs` on `WasmFunctionHandle`, and is then wrapped in a `HostFunction` like any other:

```java
import run.endive.runtime.CallResult;
import run.endive.runtime.WasmFunctionHandle;

var handle = new WasmFunctionHandle() {
    @Override
    public long[] apply(Instance instance, long... args) {
        throw new UnsupportedOperationException("called with references, use applyWithRefs");
    }

    @Override
    public CallResult applyWithRefs(Instance instance, long[] args, Object[] refArgs) {
        var p = (WasmStruct) refArgs[0];
        return CallResult.of(new long[] {p.field(0) + p.field(1)}, null);
    }
};
```
