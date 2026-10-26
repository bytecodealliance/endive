# QuickJS throughput benchmark

Measures what splitting oversized Wasm functions into JIT-eligible JVM methods is
worth, using [quickjs4j](https://github.com/roastedroot/quickjs4j) — QuickJS
compiled to Wasm and precompiled to JVM bytecode by endive's build-time compiler.

## Why this workload

QuickJS is a good stress case because its interpreter dispatch loop,
`JS_CallInternal`, compiles to about **56 KB of JVM bytecode in a single method**.

HotSpot refuses to compile a method whose bytecode exceeds `HugeMethodLimit`
(~8 KB). It does not warn; the method silently stays in the JVM's own bytecode
interpreter forever, no matter how hot it gets. So the hottest function in the
JS engine was the one piece of it that could never be optimised.

This is not specific to QuickJS. Any Wasm module with large functions — language
runtimes, emulators, anything compiled from C++ with aggressive inlining — hits
the same ceiling.

## What is compared

Two builds of quickjs4j, from **identical, unmodified quickjs4j source**, on the
same JVM, running the same script. The only difference is which build of the
endive compiler produced the precompiled bytecode inside the jar:

| | endive revision |
|---|---|
| **before** | this branch's merge-base with `main` |
| **after** | this branch |

That quickjs4j itself needs no changes is the point: this is an endive-side
improvement that existing consumers get by rebuilding.

## Running it

Requires a JDK (17+), Maven (the wrapper is used), Python 3, and git.

```bash
cd benchmarks/quickjs4j-jit
./run.sh /path/to/quickjs4j
```

With no argument it looks for a quickjs4j checkout beside the endive repo and
otherwise clones one. Roughly 10–20 minutes, most of it the four Maven builds.

Knobs:

```bash
WINDOWS=20 ITERS=10 ./run.sh /path/to/quickjs4j   # longer, steadier numbers
BASE_REV=v1.1.0 ./run.sh /path/to/quickjs4j       # compare against a release
```

Output lands in `results/`:

- `results.md` — summary table plus per-window timings
- `results.svg` — chart of both warm-up curves
- `before.csv`, `after.csv` — raw per-window data
- `before.log`, `after.log` — full console output of each run

## Reading the result

The **shape** is the evidence, more than the totals:

- **before** is flat. A method that is never JIT-compiled is as slow in the last
  window as in the first, however long it runs.
- **after** starts comparable and then improves, as HotSpot compiles the chunks
  the function was split into.

A single averaged number would hide precisely the curve that identifies the
cause.

The benchmark also checks that both builds compute the **same checksum** over
every iteration, and fails if they diverge — a throughput number means nothing if
the two sides are not doing identical work.

## Verifying the mechanism independently

The claim is "these functions now get JIT-compiled". Two ways to confirm it
directly rather than trusting the timings:

**1. What the compiler did**, from the build logs the script keeps:

```bash
grep 'chunks to stay JIT-eligible' .work/quickjs4j-after-build.log
```

Each line names a function, its bytecode size, and how many pieces it became.

**2. What HotSpot did**, by asking the JVM which methods it compiled:

```bash
java -XX:+PrintCompilation \
  -cp ".work/jars/quickjs4j-after.jar:$(cat .work/cp.txt):.work/classes" \
  JitBenchmark after 6 5 /dev/null | grep '_tc_'
```

Chunk methods are named `<function>_tc_<n>`. Compilations of them are the
optimisation happening. The same grep against `quickjs4j-before.jar` returns
nothing, because the unsplit method is never compiled at all. Column 3 of
`PrintCompilation` output is the tier: `4` is the top tier.

## Reference results

`results/` holds a committed run so the numbers can be read without running
anything. It measured **269.8 ms/iter before, 31.7 ms/iter after — 8.5x**, with
both builds producing the same checksum.

A second run on an idle machine reproduced it within 0.8% on the baseline and
0.3% on the patched build, with the same 14 functions split.

That run was on an Apple M2, macOS 26.7.1, GraalVM CE 22.0.2 (JVMCI), comparing
endive `bce75ae6` against this branch. Your absolute numbers will differ; the
ratio should not, much.

## Caveats

- Absolute numbers are machine- and JVM-specific; the ratio is the portable part.
- Splitting costs compile time — more methods to emit — paid once at build time
  for a precompiled module like this one. The script's build logs show it.
- A function that cannot be split safely is left exactly as it was: compiled, and
  still not JIT-able. The optimisation never makes a function worse, and never
  routes one to the interpreter that previously compiled.
- `before` and `after` install endive under the same Maven coordinates, so they
  are built sequentially and your local `~/.m2` is left holding the `after`
  build.
