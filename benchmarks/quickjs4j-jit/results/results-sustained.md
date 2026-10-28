# QuickJS throughput: splitting oversized functions for the JIT

Workload: **sustained execution, one engine, script precompiled**.

Same quickjs4j source and JVM throughout. `before` and `after` differ only in which build of the endive compiler precompiled the Javy plugin bytecode inside the quickjs4j jar; `redline`, where present, swaps the bytecode backend for natively compiled code and is a reference point rather than part of the before/after comparison.

- **before** &mdash; endive at `473c4266 chore(deps): bump asm.version from 9.10.1 to 9.11.1 (#245)`
- **after** &nbsp;&mdash; endive at this branch (fix(redline): keep one ASM version on the bridge's exec classpath)
- **redline** &mdash; endive `main`, native via Cranelift

Measured on `openjdk version "27.0.1.0.3" 2026-10-24`.

| | first window | steady state | best | functions split | interpreted |
|---|---|---|---|---|---|
| before | 832.7 ms | **829.4 ms** | 828.0 ms | 0 | 0 |
| after | 137.7 ms | **114.4 ms** | 113.1 ms | 14 | 0 |
| redline (native) | 41.3 ms | **40.4 ms** | 40.3 ms | n/a | n/a |
| | | **7.2x faster** | 7.3x | | |

The Redline backend, which compiles to native code via Cranelift instead of to JVM bytecode, reaches **40.4 ms** steady -- 2.8x faster than the split bytecode path, which therefore closes 91% of the distance from the unsplit baseline to native.

Redline was built from endive `473c4266 chore(deps): bump asm.version from 9.10.1 to 9.11.1 (#245)` -- that is `main`, the same revision as the `before` row, since this branch does not touch the Redline path. The runner it selected was **RedlineInstanceBuilderProvider (native, PanamaMachineFactoryProvider)**, the intended one on this JDK. Treat it as a reference point for how much of the gap to native the bytecode path closes, not as a rival measurement.

## Per-window timings

| window | before (ms) | after (ms) | redline (ms) |
|---|---|---|---|
| 0 | 832.7 | 137.7 | 41.3 |
| 1 | 834.1 | 113.3 | 40.7 |
| 2 | 833.3 | 114.1 | 40.7 |
| 3 | 830.6 | 113.4 | 40.7 |
| 4 | 830.6 | 115.3 | 40.6 |
| 5 | 832.0 | 113.1 | 40.6 |
| 6 | 833.1 | 114.5 | 40.7 |
| 7 | 829.2 | 114.1 | 40.6 |
| 8 | 828.1 | 114.6 | 40.6 |
| 9 | 830.8 | 114.9 | 40.5 |
| 10 | 830.4 | 114.6 | 40.3 |
| 11 | 830.2 | 113.7 | 40.3 |
| 12 | 829.1 | 114.3 | 40.4 |
| 13 | 828.0 | 115.1 | 40.3 |

The shape matters more than the totals. `before` is flat: a method past HotSpot's `HugeMethodLimit` is never JIT-compiled, so it is as slow in the last window as the first. `after` starts comparable and improves as HotSpot compiles the chunks.

![chart](results.svg)
