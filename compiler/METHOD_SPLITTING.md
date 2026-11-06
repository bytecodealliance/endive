# Method splitting

Endive compiles each Wasm function to one JVM method. Two JVM limits make large functions slow:

- HotSpot never JIT-compiles a method with more than 8000 bytes of bytecode
  (`-XX:HugeMethodLimit`, enforced by `-XX:+DontCompileHugeMethods`). Such a method always runs in
  the JVM's bytecode interpreter.
- The JVM rejects a method over 64 KB. Endive then runs that function in its own interpreter.

Method splitting compiles such a function into several JVM methods, called chunks, each small
enough to be JIT-compiled. It is experimental and off by default:
`MachineFactoryCompiler.builder(module).withMethodSplitting(true)`. With it off, the generated
classes are identical to a build without this feature. With it on, the output depends only on the
module and `endive.hugeMethodLimit`.

## Where the code is

- `MethodSplitter`: all of it. `plan` decides how to split one function and returns a `Plan`, or
  null to leave the function alone. `emit` generates the methods for a `Plan`.
- `Compiler.planMethodSplits`: calls `plan` for every function and prints one `Info:` line per
  split function, unless `InterpreterFallback.SILENT` is set. It runs in the `Compiler`
  constructor, before any class is written.
- `Compiler.compileFunction`: calls `emit` for a function that has a plan. Interpreted functions
  are handled before this point, so they are never split.
- `Compiler.emitInstruction` and `Compiler.emitLocalsDefaultInit`: the normal emitters. The
  splitter uses them to measure and to emit, so both see the same code.
- `Context` with `forceFrameLayout`: the slot layout of trampoline chunks.

Positions below are indices into the function's `CompilerInstruction` list. A label is a `LABEL`
instruction targeted by a branch or a `try_table`. A loop header is a label targeted by a backward
branch.

## Planning

`MethodSplitter.plan` gives up (and the function compiles as usual) at the first step that fails:

1. The function has more than 200 instructions.
2. It has no reference-typed (GC) parameters, results or locals, and not so many parameters that
   they are passed as a `long[]`.
3. It is measured: emitted once into a throwaway ASM `MethodNode`, recording the bytecode size after
   each instruction. `CodeSizeCounter` is an estimate that is usually a little high: it counts 2
   bytes for every local access and 3 padding bytes for every switch.
4. The total is over the limit: `endive.hugeMethodLimit` (default 8000), capped at 48000.
5. ASM's `Analyzer` computes the operand-stack types at every label.
6. A direct split is tried; if it finds no plan, a trampoline split.

## Direct split

The function's own method runs chunk 0 and ends by calling chunk 1, which ends by calling chunk 2,
and so on. Every chunk receives all parameters and locals as arguments:

```java
static int func_7(int p0, Memory m, Instance i) {
    int l0 = 0;
    long l1 = 0;
    // ...instructions of chunk 0...
    return chunk1_func_7(p0, m, i, l0, l1);
}

static int chunk1_func_7(int p0, Memory m, Instance i, int l0, long l1) {
    // ...instructions of chunk 1...
    return chunk2_func_7(p0, m, i, l0, l1);
}
```

A cut goes right after a label L: L is the last instruction of the earlier chunk, and a jump to L
continues in the next chunk through the call. A label can be a cut only if:

1. The operand stack is empty at L. Only locals are passed to the next chunk.
2. L is not a loop header. A loop cannot continue in another method.
3. No branch crosses the cut. A JVM jump cannot leave its method. Branches to L itself are fine,
   since L stays in the earlier chunk.

Chunks are packed greedily: walking the possible cuts in order, a chunk that would go over the limit
is cut at the last label that still fit. If nothing fits, there is no direct plan.

`br_table` is the exception to rule 3. A large switch has forward branches over most of the
function, so rule 3 alone would leave nothing to cut. For a `br_table` with at least 8 distinct
targets, each target that passes rules 1 and 2 and is not crossed by any other branch becomes a
mandatory cut. The switch sends a target in another chunk to a stub, `return chunk<k>_func_7(...)`,
that calls the chunk starting right after the target label. A final check rejects the plan if any
switch target in another chunk is not the label that ends a chunk.

Direct splits are not used for functions with a `try_table` (an exception range is per method), or
whose parameters and locals need more than the JVM's 255 argument slots. Since no loop crosses a
cut, the usual interruption checks on back edges are enough.

## Trampoline split

Used when the direct split finds no plan. Chunks no longer call each other. Each chunk returns to a
driver loop in the function's method, which calls the next chunk. This allows loops between chunks.

The locals live in a `long[]` frame passed to every chunk. With L the number of locals:

| Frame index      | Content                                            |
|------------------|----------------------------------------------------|
| 0 to L-1         | the Wasm locals, parameters first, as raw bits     |
| L                | the PC: the chunk to run next, or DONE (-1)        |
| L+1 to L+8       | operand-stack values carried into the next chunk   |

```java
static int func_7(int p0, Memory m, Instance i) {
    long[] frame = new long[L + 1 + maxSpill];  // parameters copied in, PC = 0
    for (;;) {
        checkInterruption();
        int r;
        switch ((int) frame[L]) {
            case 0: r = chunk0_func_7(frame, m, i); break;
            case 1: r = chunk1_func_7(frame, m, i); break;
            // ...
        }
        if (frame[L] == DONE) return r;
    }
}

static int chunk1_func_7(long[] frame, Memory m, Instance i) {
    frame[L] = DONE;                // a Wasm return keeps it, so the driver returns r
    int l0 = (int) frame[0];        // the locals this chunk reads or writes
    push(frame[L + 1]);             // the stack values spilled into this chunk, if any
    // ...instructions of chunk 1...
    // a branch to chunk 3: spill the stack to frame[L+1...], pc = 3, goto exit
    // falling off the end: pc = 2, goto exit
exit:
    frame[0] = l0;                  // the locals this chunk writes
    frame[L] = pc;
    return 0;                       // ignored by the driver
}
```

Rules:

- A chunk starts at instruction 0 or at a label whose operand stack has at most 8 values and no
  references (a reference cannot be stored in a `long[]`).
- A `try_table`'s declaration, protected range and handler are in one chunk.
- Every branch to another chunk targets that chunk's first instruction. It is emitted as a
  transition: store the stack into the frame, set the PC, jump to the chunk's exit. A `br_table`
  shares one transition stub per target chunk.
- A chunk loads every local it writes as well as those it reads, since a write may happen on one path
  only and the exit stores every written local.
- The driver checks for interruption on every dispatch, which covers loops between chunks. Back edges
  inside a chunk keep their usual check.

Planning (`planTrampoline`, `planTrampolineStarts`):

1. Collect the possible starts, minus any inside a `try_table` region.
2. Seed the starts by size: a new chunk begins when the bytecode since the last start exceeds the
   budget (the limit minus an estimate of the chunk overhead).
3. Repeat until nothing changes. Every branch target in another chunk becomes a start; if it cannot
   be one, there is no plan. Then each chunk is costed exactly: its bytecode, plus 64 bytes, plus 10
   bytes per local loaded or stored. The first chunk over the limit is split at the last possible
   start that fits.
4. If that fails, retry with budgets 25% and 50% smaller. Give up beyond 4096 chunks.

## Names

Chunk k of a function is the method `chunk<k>_<name>` in the function's `FuncGroup` class, where
`<name>` is the function's own method name, for example `chunk3_func_42`. The name still ends in
`_<funcId>`, so `CompilerUtil.extractFuncId` returns the function's id for chunk frames too.

## Costs and limitations

- **Compile time:** every function over 200 instructions is emitted once more to measure it.
- **Run time:** a trampoline transition costs a return to the driver, a `tableswitch`, a call, and
  the loads and stores of the locals the chunk touches.
- **Stack depth:** a direct split adds one JVM frame for each chunk a call passes through, and a
  trampoline adds one for the driver. Deep recursion overflows the JVM stack sooner.
- **The driver is not split.** It needs about 28 bytes per chunk, so with more than about 285 chunks
  it is over 8000 bytes and not JIT-compiled, and with more than about 2300 chunks it is over 64 KB
  and the function falls back to the interpreter.
- **Chunk sizes are estimates.** The trampoline estimate leaves out the transition code, so a chunk
  with many exits, such as one holding a large `br_table`, can end up over the limit and is then not
  JIT-compiled.
- **Not splittable:** functions with reference-typed parameters, results or locals; a long run of
  code with no label in it (such as the synthetic function in `MethodTooLargeTest`); and a single
  `br_table` larger than a chunk (such as CPython's `_PyUnicode_ToNumeric`).
- **Constants** (`MethodSplitter`, not configurable): 200 instructions, 8 `br_table` targets, 8
  spilled values, 4096 chunks, 64 and 10 bytes of estimated chunk overhead.

To split many more functions and exercise both strategies, lower the limit, for example with
`-Dendive.hugeMethodLimit=1500`.
