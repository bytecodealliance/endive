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

- Redline is built **from `main`** — the same revision as the `before` row —
  since post-release fixes matter to its performance and this branch does not
  touch the Redline path. On a JDK below 25 that is impossible, because its
  Panama runner targets Java 25 and cannot be compiled there; the script then
  falls back to released endive (`REDLINE_ENDIVE_VERSION`, default `1.1.0`) and
  the numbers are correspondingly weaker.
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

Two workloads run per configuration:

- **compile+exec** — a fresh engine per iteration, so JavaScript compilation is
  included. This is what a host that evaluates short scripts repeatedly does.
- **sustained** — one long-lived engine with the script compiled once, so the
  timed loop is execution alone. This separates the dispatch loop from the
  parser, and removes the advantage native code gets from having no warm-up to
  amortise.

Output lands in `results/`:

- `results.md`, `results.svg` — compile+exec summary, per-window table, chart
- `results-sustained.md`, `results-sustained.svg` — the same for sustained
- `*.csv` — raw per-window data (`-sustained` suffix for the second workload)
- `*.log` — full console output of each run, including the backend it used

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
anything. Two workloads, both on JDK 27 with Panama, on an Apple M2 /
macOS 26.7.1, comparing endive `main` against this branch:

| steady state | compile+exec | sustained execution |
|---|---|---|
| before (unsplit) | 271.8 ms | 829.4 ms |
| **after (split)** | **36.7 ms** | **114.4 ms** |
| before → after | **7.4x** | **7.2x** |
| redline (native) | 17.5 ms | 40.4 ms |
| redline vs after | 2.1x | 2.8x |

`compile+exec` builds a fresh engine every iteration, so it includes JavaScript
compilation. `sustained` uses one long-lived engine with the script precompiled,
so it is execution and nothing else. Every configuration computed the same
checksum within its workload, and the split build reached the same 14 functions
throughout.

Two things the second workload settles:

- **The speedup is not an artefact of parser-heavy work.** 7.4x and 7.2x across
  two quite different shapes, so the gain is in the dispatch loop itself rather
  than in QuickJS's parser functions.
- **Redline's lead widens under sustained execution**, 2.1x to 2.8x. The
  compile+exec figure was, if anything, flattering to the bytecode path: both
  paths spend part of that workload compiling JavaScript, which narrows the gap.
  Pure execution is where native code shows best.

Earlier runs on GraalVM CE 22 gave 8.9x for before → after on the compile+exec
workload, and a much worse Redline figure (135.3 ms) because that JDK cannot
build the Panama runner and falls back to jffi. Across five runs the
before/after ratio has come out between 7.2x and 8.9x depending on JDK and
workload; the JDKs are not a controlled comparison with each other.

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
