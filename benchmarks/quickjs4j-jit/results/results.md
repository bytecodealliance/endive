# QuickJS throughput: splitting oversized functions for the JIT

Same quickjs4j source and JVM throughout. `before` and `after` differ only in which build of the endive compiler precompiled the Javy plugin bytecode inside the quickjs4j jar; `redline`, where present, swaps the bytecode backend for natively compiled code and is a reference point rather than part of the before/after comparison.

- **before** &mdash; endive at `bce75ae6 chore(deps): bump de.mannodermaus.android-junit5 in /android-tests (#244)`
- **after** &nbsp;&mdash; endive at this branch (test(benchmarks): add Redline as a third point of comparison)
- **redline** &mdash; endive `main`, native via Cranelift

Measured on `openjdk version "27.0.1.0.3" 2026-10-24`.

| | first window | steady state | best | functions split | interpreted |
|---|---|---|---|---|---|
| before | 294.4 ms | **266.5 ms** | 265.8 ms | 0 | 0 |
| after | 96.8 ms | **36.6 ms** | 35.8 ms | 14 | 0 |
| redline (native) | 30.0 ms | **19.1 ms** | 17.8 ms | n/a | n/a |
| | | **7.3x faster** | 7.4x | | |

The Redline backend, which compiles to native code via Cranelift instead of to JVM bytecode, reaches **19.1 ms** steady -- 1.9x faster than the split bytecode path, which therefore closes 93% of the distance from the unsplit baseline to native.

Redline was built from endive `bce75ae6 (main)` -- that is `main`, the same revision as the `before` row, since this branch does not touch the Redline path. The runner it selected was **RedlineInstanceBuilderProvider (native, PanamaMachineFactoryProvider)**, the intended one on this JDK. Treat it as a reference point for how much of the gap to native the bytecode path closes, not as a rival measurement.

## Per-window timings

| window | before (ms) | after (ms) | redline (ms) |
|---|---|---|---|
| 0 | 294.4 | 96.8 | 30.0 |
| 1 | 275.2 | 53.3 | 26.8 |
| 2 | 275.0 | 44.8 | 23.9 |
| 3 | 268.9 | 43.5 | 20.5 |
| 4 | 267.9 | 42.6 | 18.4 |
| 5 | 267.0 | 41.3 | 18.6 |
| 6 | 268.8 | 38.0 | 19.2 |
| 7 | 267.4 | 38.4 | 17.8 |
| 8 | 265.8 | 37.4 | 18.4 |
| 9 | 266.9 | 38.0 | 18.7 |
| 10 | 268.1 | 37.4 | 18.8 |
| 11 | 265.9 | 36.6 | 20.9 |
| 12 | 266.3 | 36.5 | 18.3 |
| 13 | 265.8 | 35.8 | 18.3 |

The shape matters more than the totals. `before` is flat: a method past HotSpot's `HugeMethodLimit` is never JIT-compiled, so it is as slow in the last window as the first. `after` starts comparable and improves as HotSpot compiles the chunks.

![chart](results.svg)
