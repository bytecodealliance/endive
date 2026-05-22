---
slug: finding-a-jvm-jit-bug-the-hard-way
title: 'Finding a JVM JIT Bug the Hard Way'
authors: [andreaTP]
tags: [endive, wasm, bug, jvm]
---

![Finding a JVM JIT Bug](finding-a-jvm-jit-bug.png)

In early 2025, a user reported intermittent wrong results using the build-time compiler. Not a crash, just a quietly wrong answer. A year of debugging, dead ends, and creative workarounds later, we traced the problem to a bug in the JVM's C2 JIT compiler, and had to build an entire compiler from scratch in 3 days just to prove it.

<!-- truncate -->

## The wrong answer

In February 2025, a user opened [an issue](https://github.com/dylibso/chicory/issues/755) against the project. They were running a large Wasm module, built from a C++ codebase, on the JVM, and something was wrong: an operation that reverses a string was silently producing incorrect results, though not consistently. It only happened on Java 17 Temurin, only with the build-time compiler, and only when the Wasm module was large enough.

The key word is *silently*. There was no exception and no segfault, just a comparison operation deep inside the compiled code returning the wrong boolean while everything downstream acted on that corrupted result. Correctness bugs are the most dangerous kind: crashes tell you something is wrong; wrong answers let you ship broken software with confidence.

Before you worry: this bug requires an extraordinary set of conditions to trigger. If you're writing ordinary Java applications, the odds of encountering it are vanishingly small. But the story of how we found, worked around, and finally fixed it is worth telling.

## Not our bug

[Edoardo Vacchi](https://github.com/evacchi) and I paired on the investigation with the reported module. We narrowed the problem to the implementation of a single WebAssembly opcode: `I32_GE_U`, the unsigned greater-than-or-equal comparison. Here's the entire implementation:

```java
public static int I32_GE_U(int a, int b) {
    return Integer.compareUnsigned(a, b) >= 0 ? TRUE : FALSE;
}
```

One line. `Integer.compareUnsigned` is a standard JDK method that has existed since Java 8, and it is a platform intrinsic, meaning the JIT compiler replaces it with optimized machine code rather than executing the Java implementation. There is no bug in this code.

But when we added a single JVM flag, `-XX:CompileCommand=dontinline,com/dylibso/chicory/runtime/OpcodeImpl.I32_GE_U`, the problem vanished. That flag tells the C2 JIT compiler not to inline the method. The fact that preventing inlining fixed the issue pointed the finger squarely at how C2 *optimizes* the inlined code, not at our logic.

This was our introduction to a genuine [Heisenbug](https://en.wikipedia.org/wiki/Heisenbug): a bug whose behavior changes when you try to observe it. Adding a `System.out.println` inside the method made it vanish because the print statement changes C2's inlining decisions. Attaching a debugger had the same effect since it prevents certain JIT optimizations. Even reducing the Wasm module to a smaller test case killed the reproduction, because the JIT needs approximately 45 million function calls to build up enough type profile information to trigger the aggressive optimization that contains the defect.

Every tool in a debugger's standard toolkit made the problem disappear.

## Living with the bug

We couldn't fix the JVM, and we couldn't wait for a fix we didn't yet have. So we got creative with workarounds.

The first approach, suggested by [Hiram Chirino](https://github.com/chirino), was a [megamorphic dispatch trick](https://github.com/dylibso/chicory/pull/844). The idea: if C2 only miscompiles the code when it inlines through a monomorphic call site, we can force C2 to give up on inlining by making the call site look polymorphic. At static initialization time, we cycle through different lambda implementations of the same interface, training the JIT to see the call site as megamorphic (too many receiver types to optimize):

```java
static {
    I32GEUFunc noop1 = (a, b) -> a;
    I32GEUFunc noop2 = (a, b) -> b;

    for (int i = 0; i < 1000; i++) {
        i32geuFunc = noop1;
        MemCopyWorkaround.i32_ge_u(0, 0);
        i32geuFunc = noop2;
        MemCopyWorkaround.i32_ge_u(0, 0);
    }

    i32geuFunc = (a, b) -> OpcodeImpl.I32_GE_U(a, b);
}
```

It's an ugly hack, but it keeps the user mostly safe. We [extended it](https://github.com/dylibso/chicory/pull/1178) to cover all affected code paths and added a CI test using a large Wasm module to catch regressions. The workaround only activates on Java 17 and earlier.

There's an irony here: on Java 18+, the bug was already masked. An [entirely unrelated optimization](https://github.com/openjdk/jdk/pull/6101) by [Quan Anh Mai](https://github.com/merykitty) in November 2021 (JDK-8276162, "Optimise unsigned comparison pattern") taught C2 to recognize the `Integer.compareUnsigned` pattern earlier in the pipeline and lower it directly to an unsigned machine comparison. With that optimization in place, by the time C2 reaches the buggy if-folding stage, the signed comparison pattern it would have miscompiled no longer exists. The bug is still there, it just never fires because its input has already been optimized away.

## The reproducer problem

The workarounds bought us time, but they weren't a fix. I had a bug in CI, workarounds in production, and a deep suspicion about what was going wrong inside C2. What I didn't have was a way to prove it to the OpenJDK team.

The OpenJDK project reasonably requires Java source code reproducers with bug reports. They need something that compiles with `javac`, runs with standard JDK tools, and demonstrates the problem in isolation. But our build-time compiler generates JVM bytecode directly from Wasm, freely using `goto` and labels in patterns that have no direct Java source representation. You can't just decompile it.

I tried. Every major decompiler (CFR, Procyon, Fernflower) either choked on the input size (the generated bytecode file was enormous) or produced non-compilable output, with issues especially around control flow in nested blocks. I also tried [HotSpot's replay compilation](https://cr.openjdk.org/~thartmann/talks/2020-Debugging_HotSpot.pdf): I could reproduce the bug during replay, but the output didn't give enough insight to isolate the root cause.

The only consistent reproducer we had was [wat2wasm](https://github.com/WebAssembly/wabt), a tool from the [WebAssembly Binary Toolkit (WABT)](https://github.com/WebAssembly/wabt) that converts WebAssembly text format to binary format, roughly 195,000 lines of C++ compiled to a single large Wasm module. I tried reducing it. The original generated code was over 213,000 lines of Java. I managed to get the generated code down to about 40,000 lines, but I couldn't go further. The bug required all 294 functions in the module to be present, approximately 45 million function calls to build C2's type profiles, and specific bytecode patterns that emerged only from the full module. Removing any piece meant C2 would never reach the optimization threshold.

I spent months chasing down the issue. Multiple branches in [my fork](https://github.com/andreaTP/chicory) (`decompiling-attempt1`, `JDK-8376400-reproducer`, and others) document the dead ends. Every approach that should have worked didn't. The Heisenbug lived up to its name.

## Building a compiler to catch a compiler bug

By February 2026, I had the bug on my mind for nearly a year. The megamorphic workaround was holding enough, but the real fix was sitting behind a wall I couldn't climb: I needed Java source code, and the toolchain produced Java bytecode.

What if I built a different toolchain?

Instead of trying to decompile bytecode back into Java sources, what if I compiled WebAssembly directly to Java source code, skipping bytecode entirely? The generated Java would be verbose and mechanical, but it would be compilable with `javac`. It was a bet: if `javac` happened to produce bytecode with the same patterns that triggered the C2 bug, I'd have my reproducer.

The idea was clear, but the scope was daunting. A source compiler that could handle something the size of `wat2wasm` is a real compiler: it needs to handle over a hundred opcodes, translate WebAssembly's structured control flow (blocks, loops, `br_if`, `br_table`) into Java's `while`/`switch`/`break` constructs, split methods to stay under the JVM's 64KB method size limit, and handle edge cases in memory operations, type conversions, and stack manipulation.

I wouldn't have attempted it without a machine helping me.

### 3 days later

In February 2026, I started using [Claude](https://claude.ai) and wanted to test it on something that was both low-risk (a standalone, one shot tool, not a change to the runtime) and hard enough to be a meaningful test. Building a Wasm-to-Java-source compiler fit perfectly.

The approach was informally spec-driven from the start. We already had a test suite generator that creates Java test cases from the official [WebAssembly spec test suite](https://github.com/WebAssembly/testsuite). I hooked the source compiler into that generator on day one, so every opcode implementation was validated against the spec as it was written. From there, I used real-world Wasm modules and the WASI testsuite to catch edge cases the spec tests didn't cover.

Claude handled the mechanical work: porting opcodes one by one following established patterns, generating the boilerplate for type conversions, and iterating through the hundreds of Wasm instructions that all follow similar structures. I steered the architecture: the control flow translation strategy, the method splitting heuristic to stay under the JVM's 64KB method limit, and debugging the edge cases where WebAssembly's semantics diverge from Java's.

The spec test suite provided continuous validation: if a change broke something, we knew immediately which opcode and which test case failed. This tight feedback loop made it possible to move fast without accumulating hidden bugs or making regressions.

In a little more than 3 days of very hard work, I went from zero to a compiler that passed over 25,000 spec tests and all WASI tests. Without the LLM, I would never have started: the effort of writing a full source compiler solo would have been hard to justify against the uncertain payoff. With it, the turnaround was fast enough that building the tool was worth more than spending another month trying to reduce the bytecode reproducer.

### The moment of truth

Compiling `wat2wasm` through the source compiler produced approximately 200,000 lines of Java source code.

When I compiled that Java source with `javac`, ran it on Java 17, and fed it a large enough `.wat` input, the program produced wrong results. Same `out of bounds memory access` error, same non-deterministic behavior, same Heisenbug, now reproduced in pure Java source code. 🎉

I finally had what the OpenJDK team needed: a self-contained, `javac`-compilable reproducer of a C2 JIT compiler bug. [PR #1200](https://github.com/dylibso/chicory/pull/1200) documents the source compiler and the reproducer.

## The 20-line fix

With the 200,000-line reproducer in hand, [Roland Westrelin](https://github.com/rwestrel) took a look. He did what I couldn't: he understood the C2 optimization pipeline well enough to distill our massive reproducer into a [20-line test case](https://github.com/openjdk/jdk/pull/30677):

```java
private static void test1(int i) {
    int v;
    // (1) C2 sees this signed comparison
    if (i + MIN_VALUE >= 16 + Integer.MIN_VALUE) {
        v = 0;
    } else {
        v = 1;
    }
    // (2) Uncommon trap, C2 assumes this is never taken
    if (v == 0) {
        throw new RuntimeException("never taken");
    }
    // (3) Second signed comparison, C2 folds (1) and (3)
    //     into a single unsigned comparison
    if (i + MIN_VALUE < 8 + Integer.MIN_VALUE) {
        taken1++;
    }
}
```

The bug lives in the interaction of three C2 optimizations:

1. **Signed comparisons with uncommon traps.** C2 compiles the two `if` statements and records that the `throw` branch is never taken (an "uncommon trap", a deoptimization point for rare paths).

2. **Split-if.** C2's `do_split_if()` optimization duplicates control flow through a Phi node, specializing each branch. This modifies the state captured by the uncommon trap.

3. **If-folding.** `IfNode::fold_compares()` combines the two signed comparisons into a single, more efficient unsigned comparison. But it doesn't know that split-if has already modified the uncommon trap's saved state. When deoptimization fires at runtime, execution resumes at the wrong bytecode point with corrupted state.

The fix is surgical: a `_safe_for_fold_compare` flag. When `do_split_if()` modifies an uncommon trap, it marks it as unsafe for if-folding. The fold_compares optimization checks this flag before proceeding. 369 additions (mostly tests), 3 deletions. The fix was [integrated](https://github.com/openjdk/jdk/pull/30677) on April 30, 2026.

As one of the reviewers noted: *"these kind of bugs with wrong safepoint states are really hard to catch."*

## Should you worry?

No.

This bug requires a very specific convergence of conditions:

- **A particular code pattern**: multiple signed integer comparisons that C2 can fold into unsigned comparisons, with uncommon traps that the split-if optimization modifies. This pattern is rare in handwritten Java.
- **Enormous JIT warmup**: approximately 45 million function calls to build the type profile data that triggers C2's most aggressive optimizations. Most Java methods never reach this threshold.
- **JDK 11 through 17**: on JDK 18 and later, an unrelated optimization (JDK-8276162) masks the bug entirely.
- **Generated code patterns**: the comparison-heavy, uniform-dispatch patterns that WebAssembly runtimes produce are unusual. Handwritten Java code tends toward more diverse control flow that doesn't trigger the specific optimization sequence.

We hit these conditions because we are aggressively leveraging C2's inlining and JIT optimizations to run Wasm as fast as possible on the JVM. The build-time compiler is designed to produce code that C2 can optimize deeply: millions of tiny functions, uniform opcode dispatch, comparison-heavy control flow at enormous scale. That's the whole point, and it's what makes the engine fast. It also means we exercise the JVM in ways that handwritten Java rarely does.

In the meantime, the workaround protects all users automatically on affected JDK versions. The upstream fix will ship in a future JDK release.

## What we learned

**Correctness bugs are scarier than crashes.** A wrong comparison result is silent, and downstream failures can look completely unrelated to the root cause. If the reporter hadn't been testing carefully, this could have gone unnoticed for much longer.

**Heisenbugs demand creative approaches.** Every standard debugging technique (print statements, debuggers, test reduction, bisection) actively prevented this bug from manifesting. When observation collapses the phenomenon you're investigating, you need to find indirect evidence.

**Sometimes building new tooling beats reducing existing artifacts.** I spent months trying to shrink a 213,000-line reproducer. In the end, building an entirely new compiler in 3 days produced a better result. The lesson isn't "always build new tools", it's "recognize when reduction has hit a wall and consider alternatives."

**Open source ecosystems work.** A user reported the bug, we investigated and built workarounds, [David Lloyd](https://github.com/dmlloyd) helped file the upstream issue, and Roland Westrelin diagnosed the C2 root cause and wrote the fix. This chain from user report to compiler fix only works because the code is open and the communities are connected.

If you care about Java, WebAssembly, and the future of really portable software, [come build with us](https://github.com/bytecodealliance/endive).
