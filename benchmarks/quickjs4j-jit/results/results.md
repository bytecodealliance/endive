# QuickJS throughput: splitting oversized functions for the JIT

Identical quickjs4j source and JVM in both rows. The only difference is which build of the endive compiler precompiled the Javy plugin bytecode inside the quickjs4j jar.

- **before** &mdash; endive at `bce75ae6 chore(deps): bump de.mannodermaus.android-junit5 in /android-tests (#244)`
- **after** &nbsp;&mdash; endive at this branch (test(benchmarks): add a reproducible QuickJS throughput benchmark)

| | first window | steady state | best | functions split | interpreted |
|---|---|---|---|---|---|
| before | 305.4 ms | **269.8 ms** | 269.2 ms | 0 | 0 |
| after | 123.3 ms | **31.7 ms** | 29.2 ms | 14 | 0 |
| | | **8.5x faster** | 9.2x | | |

Both builds computed the same checksum (`741297138`), so the two rows ran the same work.

## Per-window timings

| window | before (ms) | after (ms) |
|---|---|---|
| 0 | 305.4 | 123.3 |
| 1 | 283.2 | 44.4 |
| 2 | 280.3 | 48.1 |
| 3 | 274.3 | 37.9 |
| 4 | 274.2 | 49.1 |
| 5 | 274.4 | 41.0 |
| 6 | 272.0 | 34.2 |
| 7 | 271.5 | 36.1 |
| 8 | 273.3 | 33.0 |
| 9 | 271.5 | 33.9 |
| 10 | 270.7 | 31.6 |
| 11 | 269.2 | 36.2 |
| 12 | 269.6 | 29.8 |
| 13 | 269.8 | 29.2 |

The shape matters more than the totals. `before` is flat: a method past HotSpot's `HugeMethodLimit` is never JIT-compiled, so it is as slow in the last window as the first. `after` starts comparable and improves as HotSpot compiles the chunks.

![chart](results.svg)
