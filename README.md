# Endive

<p align="center">
  <picture>
    <img width="200" src="endive.png">
  </picture>
  <br>
  <strong>A <a href="https://bytecodealliance.org/">Bytecode Alliance</a> hosted project</strong>
  <br><br>
  <a href="https://endive.run/">Website</a> |
  <a href="https://endive.run/docs/#getting-started">Getting started</a> |
  <a href="https://endive.run/blog">Blog</a> |
  <a href="/CONTRIBUTING.md">Contributing</a>
</p>

[![Interpreter Test Results](https://gist.githubusercontent.com/andreaTP/69354d1cc6cf23e4c3c4a9a8daf7ea15/raw/badge-interpreter.svg)](https://gist.githubusercontent.com/andreaTP/69354d1cc6cf23e4c3c4a9a8daf7ea15/raw/badge-interpreter.svg)
[![Compiler Test Results](https://gist.githubusercontent.com/andreaTP/69354d1cc6cf23e4c3c4a9a8daf7ea15/raw/badge-compiler.svg)](https://gist.githubusercontent.com/andreaTP/69354d1cc6cf23e4c3c4a9a8daf7ea15/raw/badge-compiler.svg)
[![WASI Test Results](https://gist.githubusercontent.com/andreaTP/69354d1cc6cf23e4c3c4a9a8daf7ea15/raw/badge-wasi.svg)](https://gist.githubusercontent.com/andreaTP/69354d1cc6cf23e4c3c4a9a8daf7ea15/raw/badge-wasi.svg)

[![Zulip](https://img.shields.io/badge/zulip-join_chat-brightgreen.svg)](https://bytecodealliance.zulipchat.com/#narrow/stream/endive)

Endive is a JVM native WebAssembly runtime. It allows you to run WebAssembly programs with
zero native dependencies or JNI. Endive can run Wasm anywhere that the JVM can go. It is designed with
simplicity and safety in mind.

Endive is a fork of [Chicory](https://github.com/dylibso/chicory) by Dylibso, Inc.
We thank Dylibso for the incubation period and their foundational work on this project.

> *Reach out to us*: let us know what you are building with Endive.
> [Join our Zulip chat](https://bytecodealliance.zulipchat.com/#narrow/stream/endive).

Get started now with the [official documentation](https://endive.run/docs/)

## Why?

There are a number of mature Wasm runtimes to choose from to execute a Wasm module.
To name a few [v8](https://v8.dev/), [wasmtime](https://wasmtime.dev/), [wasmer](https://wasmer.io/), [wasmedge](https://wasmedge.org/), [wazero](https://wazero.io/) etc.

Although these can be great choices for running a Wasm application, embedding them into your existing
Java application has some downsides. Because these runtimes are written in C/C++/Rust/etc, they must be distributed
and run as native code. This causes two main friction points:

### 1. Distribution

If you're distributing a Java library (jar, war, etc), you must now distribute along with it a native object targeting the correct
architecture and operating system. This matrix can become quite large. This eliminates a lot of the simplicity and original benefit of shipping Java code.

### 2. Runtime

At runtime, you must use FFI to execute the module. When you do, you're effectively escaping the safety and observability of the JVM. Having a pure JVM runtime means all your
security and memory guarantees, and your tools, can stay in place.

## Goals

* Be the default runtime for Wasm on the JVM
* Be as safe as possible
* Make it easy to run Wasm in any JVM environment without native code, including very restrictive environments
* Fully support the core Wasm spec
* Make integration with Java (and other host languages) easy and idiomatic

## Roadmap

Endive development builds on years of work started in September 2023 as Chicory.
If you have an interest in working on any of these please reach out in Zulip!

### Completed

* [x] Wasm binary parser
* [x] Simple bytecode interpreter
* [x] Generate JUnit tests from wasm test suite
* [x] All tests green with the interpreter (correctness)
* [x] Validation logic (safety)
* [x] v1.0 API (stability and dx)
* [x] Decoupled interpreter and compiler "engines"
* [x] AOT compiler passes all the same specs as interpreter
* [x] WASIp1 Support (including test gen)
* [x] SIMD Support
* [x] Tail Call (interpreter and compiler)
* [x] Compiler out of experimental
* [x] Exception Handling
* [x] Threads Support
* [x] Extended Constant Expressions
* [x] GC support
* [x] Multi-Memory Support

### Ongoing

* [ ] Performance
* [ ] WASIp2 Support

## On the press

- [Chicory: A Zero Dependency Wasm Runtime for the JVM](https://www.javaadvent.com/2023/12/chicory-wasm-jvm.html) on [Java Advent 2023](https://www.javaadvent.com/2023/12)
- [Chicory - a WebAssembly Interpreter Written Purely in Java with Zero Native Dependencies](https://www.infoq.com/news/2024/05/chicory-wasm-java-interpreter/) on [InfoQ](https://www.infoq.com)
- [Chicory: Write to WebAssembly, Overcome JVM Shortcomings](https://thenewstack.io/chicory-write-to-webassembly-overcome-jvm-shortcomings/) on [The New Stack](https://thenewstack.io)
- [Meet Chicory, exploit the power of WebAssembly on the server side! by Andrea Peruffo](https://www.youtube.com/watch?v=7a1yrDSh9rA) (Devoxx BE 2024)
- [WebAssembly, the Safer Alternative to Integrating Native Code in Java](https://www.infoq.com/articles/sqlite-java-integration-webassembly/) on [InfoQ](https://www.infoq.com)
- [Chicory: Creating a Language-Native Wasm Runtime by Benjamin Eckel / Andrea Peruffo](https://www.youtube.com/watch?v=00LYdZS0YlI) (Wasm I/O 2024)
- [Chicory, a JVM Native WebAssembly Runtime by Benjamin Eckel](https://youtu.be/acF_cJ70n04?si=jpMAfAmjl5UaEWWa) (Dylibso Insiders)
- [WebAssembly the ace up the sleeve of your Java and Quarkus apps](https://www.youtube.com/live/YY5he2pdv8Q?si=tJCXJbfLXDtRxbh-) (Quarkus Insights 206)
- [The Chicory Photo Album: Celebrating 1.0.0 and a Year of Wasm](https://www.javaadvent.com/2024/12/wasm-chicory-1.html) on [Java Advent 2024](https://www.javaadvent.com/2024/12)
- [Wazero vs Chicory: An In-Depth Comparison Between Two Language-Native Wasm Runtimes by Edoardo Vacchi](https://archive.fosdem.org/2025/schedule/event/fosdem-2025-4961-wazero-vs-chicory-an-in-depth-comparison-between-two-language-native-wasm-runtimes/) (FOSDEM 2025)
- [WASM in the Enterprise: Secure, Portable, and Ready for Business by Andrea Peruffo](https://www.infoq.com/presentations/wasm-enterprise/) (QCon London 2025)
- [A Go CEL Policy Engine in Java, with Quarkus Chicory](https://quarkus.io/blog/k8s-style-CEL-with-quarkus-chicory/) on [Quarkus Blog](https://quarkus.io/blog/)
- [Introduction to the Chicory Native JVM WebAssembly Runtime](https://www.baeldung.com/chicory-native-jvm-webassembly-runtime) on [Baeldung](https://www.baeldung.com)
- [Bring WebAssembly to the JVM. How Chicory Is Powering a New Generation of Java Libraries](https://www.javaadvent.com/2025/12/chicory-webassembly-on-the-jvm.html) on [Java Advent 2025](https://www.javaadvent.com/2025/12)
- [The State of Zero-Dependency Wasm: A 2026 Update from Wazero and Chicory](https://www.youtube.com/watch?v=RjLXovPbU90) (Wasm I/O 2026)

## Prior Art

* [asmble](https://github.com/cretz/asmble)
* [kwasm](https://github.com/jasonwyatt/KWasm)
* [wazero](https://wazero.io/)

## Who uses Endive?

See [ADOPTERS.md](ADOPTERS.md) for the full list of organizations and projects using Endive.
