---
sidebar_position: 2
sidebar_label: Build-time Compiler
title: Build-time Compiler
---

The build-time compiler translates Wasm functions to JVM bytecode during your build and stores them as `.class` files that you package in your application.

:::warning[Security Consideration]
The compiler generates JVM bytecode without a separate verification pass. Only compile modules you trust. See [Security Model](/docs/security/overview).
:::

Compared to the [runtime compiler](runtime-compiler.md):

- nothing is compiled at instantiation
- no reflection and no ASM at runtime, so it works with `native-image`
- the module ships as a self-contained jar

Modules that use SIMD (`v128`) instructions cannot be compiled and must run in the interpreter.

## Maven

Configure the plugin:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>run.endive</groupId>
      <artifactId>endive-compiler-maven-plugin</artifactId>
      <executions>
        <execution>
          <id>compiler-gen</id>
          <goals>
            <goal>compile</goal>
          </goals>
          <configuration>
            <!-- Translate the Wasm binary `add` into bytecode -->
            <wasmFile>src/main/resources/add.wasm</wasmFile>
            <!-- Name of the generated class to be used -->
            <name>org.acme.wasm.Add</name>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Then use the generated class as the machine factory:

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.runtime.InterpreterMachine;

docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "your.wasm");

// mocking up the generated code
class Add {

    public static WasmModule load() {
      return Parser.parse(new File("your.wasm"));
    }

    public static Machine create(Instance instance) {
        return new InterpreterMachine(instance);
    }

}
```
-->

```java
import run.endive.runtime.Instance;

// load the bundled module
var module = Add.load();

// instantiate the module with the pre-compiled code
var instance = Instance.builder(module).
        withMachineFactory(Add::create).
        build();
```

### Parameters

The ones you are likely to set:

| Parameter | Description |
|---|---|
| `wasmFile` | The Wasm module to compile. Required. |
| `name` | Fully qualified name of the generated class. Required. |
| `moduleInterface` | Fully qualified name for which to generate typed `_ModuleExports` and `_ModuleImports` wrappers. See [below](#generating-module-exports-and-imports). |
| `interpreterFallback` | What to do with a function too large for a JVM method: `FAIL` (default), `WARN` or `SILENT`. With `WARN` or `SILENT` the function runs in the interpreter. |
| `interpretedFunctions` | Comma-separated function indexes to interpret instead of compile. |
| `methodPrefixer` | Set to `run.endive.compiler.NameSectionMethodPrefixer` to name the compiled methods after the module's name section, so stack traces and profiler output show the Wasm function names. Any `MethodPrefixer` with a public no-argument constructor on the plugin's classpath is accepted. |

Print the full list with:

```bash
mvn help:describe -DgroupId=run.endive -DartifactId=endive-compiler-maven-plugin -Ddetail
```

### Generating module exports and imports

The build-time compiler can also generate typed Java wrappers for a module's exports and imports,
eliminating the need for the [`@WasmModuleInterface` annotation](../annotations/index.md#wasmmoduleinterface) and the annotation processor setup.

Add the `moduleInterface` parameter to the plugin configuration:

```xml
<configuration>
  <wasmFile>src/main/resources/demo.wasm</wasmFile>
  <name>org.acme.wasm.DemoModule</name>
  <moduleInterface>org.acme.wasm.Demo</moduleInterface>
</configuration>
```

This generates `Demo_ModuleExports` and `Demo_ModuleImports` classes alongside the compiled module.
You can then use them directly in your code without any annotation:

<!--
```java
//DEPS run.endive:docs-lib:999-SNAPSHOT
//DEPS run.endive:runtime:999-SNAPSHOT

import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.runtime.InterpreterMachine;

docs.FileOps.copyFromWasmCorpus("count_vowels.rs.wasm", "demo.wasm");

// mocking up the generated code
class DemoModule {

    public static WasmModule load() {
      return Parser.parse(new File("demo.wasm"));
    }

    public static Machine create(Instance instance) {
        return new InterpreterMachine(instance);
    }

}

class Demo_ModuleExports {
    public Demo_ModuleExports(Instance instance) {}
}
```
-->

```java
var instance = Instance.builder(DemoModule.load()).
        withMachineFactory(DemoModule::create).
        build();
var exports = new Demo_ModuleExports(instance);
```

### IDE shortcomings

In some IDEs the sources generated under the standard folder `target/generated-sources` are not automatically recognized.
To overcome this limitation you can use an additional Maven Plugin for a smoother IDE experience:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <executions>
        <execution>
        <id>addSource</id>
        <phase>generate-sources</phase>
        <goals>
            <goal>add-source</goal>
        </goals>
        <configuration>
            <sources>
                <source>${project.build.directory}/generated-sources/endive-compiler</source>
            </sources>
        </configuration>
        </execution>
    </executions>
</plugin>
```

## Gradle [community]

Gradle users can leverage the [wasm2class-gradle-plugin](https://github.com/illarionov/wasm2class-gradle-plugin),
a third-party plugin that serves as an alternative to the Maven plugin, running the build-time compiler
and enabling the use of pre-compiled Wasm code in Java, Kotlin, and Android projects.

To set it up, make sure MavenCentral is listed as a repository in the `pluginManagement` block of your `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Configuration example in the `build.gradle.kts` file for the module:

```kotlin
plugins {
    id("at.released.wasm2class.plugin") version "<latest version>"
}

wasm2class {
    modules {
        // Target package for the generated classes
        targetPackage = "org.acme.wasm"
        //  Use "Add" as the base name for generated classes
        create("Add") {
            // Translate `add.wasm` into bytecode
            wasm = file("src/main/resources/add.wasm")
        }
    }
}
```

This generates the class `org.acme.wasm.Add`, which you can use to instantiate the module just like shown earlier
in the Maven example.

<!--
```java
docs.FileOps.writeResult("docs/execution", "build-time-compiler.md.result", "empty");
```
-->
