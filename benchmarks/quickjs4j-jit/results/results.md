# QuickJS throughput: splitting oversized functions for the JIT

Workload: **compile+exec, a fresh engine per iteration**.

Same quickjs4j source and JVM throughout. `before` and `after` differ only in which build of the endive compiler precompiled the Javy plugin bytecode inside the quickjs4j jar; `redline`, where present, swaps the bytecode backend for natively compiled code and is a reference point rather than part of the before/after comparison.

- **before** &mdash; endive at `473c4266 chore(deps): bump asm.version from 9.10.1 to 9.11.1 (#245)`
- **after** &nbsp;&mdash; endive at this branch (fix(redline): keep one ASM version on the bridge's exec classpath)
- **redline** &mdash; endive `main`, native via Cranelift

Measured on `openjdk version "27.0.1.0.3" 2026-10-24`.

| | first window | steady state | best | functions split | interpreted |
|---|---|---|---|---|---|
| before | 296.4 ms | **271.8 ms** | 271.2 ms | 0 | 0 |
| after | 101.3 ms | **36.7 ms** | 36.2 ms | 14 | 0 |
| redline (native) | 27.3 ms | **17.5 ms** | 17.2 ms | n/a | n/a |
| | | **7.4x faster** | 7.5x | | |

The Redline backend, which compiles to native code via Cranelift instead of to JVM bytecode, reaches **17.5 ms** steady -- 2.1x faster than the split bytecode path, which therefore closes 92% of the distance from the unsplit baseline to native.

Redline was built from endive `473c4266 chore(deps): bump asm.version from 9.10.1 to 9.11.1 (#245)` -- that is `main`, the same revision as the `before` row, since this branch does not touch the Redline path. The runner it selected was **RedlineInstanceBuilderProvider (native, PanamaMachineFactoryProvider)**, the intended one on this JDK. Treat it as a reference point for how much of the gap to native the bytecode path closes, not as a rival measurement.

## Per-window timings

| window | before (ms) | after (ms) | redline (ms) |
|---|---|---|---|
| 0 | 296.4 | 101.3 | 27.3 |
| 1 | 280.6 | 50.6 | 24.3 |
| 2 | 277.7 | 47.4 | 24.9 |
| 3 | 274.7 | 45.6 | 26.4 |
| 4 | 272.9 | 40.3 | 18.4 |
| 5 | 274.6 | 39.8 | 18.6 |
| 6 | 272.2 | 37.3 | 19.2 |
| 7 | 273.3 | 37.4 | 17.7 |
| 8 | 271.6 | 36.7 | 17.3 |
| 9 | 273.2 | 37.0 | 17.2 |
| 10 | 271.8 | 37.8 | 17.3 |
| 11 | 271.2 | 36.3 | 18.4 |
| 12 | 272.3 | 36.7 | 17.2 |
| 13 | 271.6 | 36.2 | 17.2 |

The shape matters more than the totals. `before` is flat: a method past HotSpot's `HugeMethodLimit` is never JIT-compiled, so it is as slow in the last window as the first. `after` starts comparable and improves as HotSpot compiles the chunks.

![chart](results.svg)
