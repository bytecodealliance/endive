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

So "before" is endive `main`, not a release: the comparison is against the
commit this branch is actually based on.

That quickjs4j itself needs no changes is the point: this is an endive-side
improvement that existing consumers get by rebuilding.

### The optional third configuration: Redline

Endive's experimental Redline backend compiles a module to native code through
Cranelift instead of to JVM bytecode. Measuring it alongside answers a fair
question about this change — whether bytecode-level work is the right place to
optimise at all, or whether the native path already wins.

It runs automatically when the quickjs4j checkout has a `redline/` module,
which arrived in [roastedroot/quickjs4j#209](https://github.com/roastedroot/quickjs4j/pull/209)
(still a draft at the time of writing, so point `run.sh` at that branch):

```bash
git fetch origin refs/pull/209/head:pr209 && git checkout pr209   # in quickjs4j
./run.sh /path/to/quickjs4j                                       # in this directory
```

Without it the configuration is skipped and the run still produces the
before/after comparison.

Two things to know when reading its numbers:

- Redline is built against a **released** endive (`REDLINE_ENDIVE_VERSION`,
  default `1.1.0`), because its Panama runner targets Java 25 and cannot be
  compiled by an older JDK. This branch does not touch the Redline path, so
  pinning it is harmless.
- **Below Java 25 the native path runs on jffi, not Panama**, and jffi's
  per-call overhead is much higher. This benchmark builds a fresh engine per
  iteration, so it crosses the FFI boundary constantly — close to a worst case
  for jffi. Numbers from a pre-25 JDK are a floor for Redline, not its best
  showing. The run reports which runner it selected, so the output says which
  case you are looking at.

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

`results/` holds a committed run (the JDK 27 one) so the numbers can be read
without running anything. Both JDKs measured, on an Apple M2 / macOS 26.7.1,
comparing endive `bce75ae6` against this branch:

| steady state | JDK 27 (Panama) | GraalVM CE 22 (jffi) |
|---|---|---|
| before (unsplit) | 274.0 ms | 281.5 ms |
| **after (split)** | **36.6 ms** | **31.6 ms** |
| before → after | **7.5x** | **8.9x** |
| redline (native) | **17.5 ms** | 135.3 ms |

Every configuration computed the same checksum, and the split build reached the
same 14 functions throughout. Across four runs the before/after ratio came out
7.5x, 8.5x, 8.5x and 8.9x; best-window against best-window is steadier. Call it
**7.5–8.9x depending on JDK**.

Two things to read carefully:

- **Redline is faster than the split bytecode path** — 17.5 ms against 36.6 ms,
  so about 2.1x, with the bytecode path closing roughly 93% of the distance from
  the unsplit baseline to native. Redline needs natively compiled code per
  target and an FFI runner; the bytecode path is the portable pure-JVM one. They
  are not substitutes, and the native figure is the useful ceiling to measure
  against.
- **The jffi column is not a fair reading of Redline.** Below Java 25 the Panama
  runner cannot be built, and jffi's per-call overhead dominates a benchmark
  that builds a fresh engine every iteration. It is kept only to show how much
  the runner matters.

The two JDK columns are each internally consistent but are not a controlled
comparison *with each other* — 22 and 27 differ in more than the FFI path, so
read down a column, not across.

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
