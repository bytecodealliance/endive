# Method splitting

Endive compiles each WASM function to one JVM method. HotSpot never JIT-compiles a method
larger than 8000 bytes of bytecode (`-XX:HugeMethodLimit`, enforced by
`-XX:+DontCompileHugeMethods`), and the JVM rejects methods over 64 KB, which Endive then runs in
its interpreter. Method splitting compiles such a function into several methods that are each
under 8000 bytes. It is experimental and enabled per compilation.

## Where the code is

- `MethodSplitter`: all planning and emission. `MethodSplitter.plan` returns a `Plan` (either the
  direct-split cuts, or the trampoline chunk starts with the operand-stack types spilled into
  each), and `MethodSplitter.emit` generates the methods for it.
- `Compiler.planMethodSplits`: runs the planner for every function and prints one `Info:` line per
  split function, unless `InterpreterFallback.SILENT` is set.
- `Compiler.compileFunction`: emits a function through `MethodSplitter.emit` when it has a plan.
- `Compiler.emitInstruction` and `Compiler.emitLocalsDefaultInit`: the normal emitters, shared
  with the splitter so measured and emitted code are the same.
- `Context` with `forceFrameLayout`: the slot layout of trampoline chunks.
- `MachineFactoryCompiler.Builder.withMethodSplitting` and `Compiler.Builder.withMethodSplitting`:
  the switch. When it is off, `Compiler.splitPlans` is empty and none of this runs. When it is on,
  the compile cache key gets a `split-` prefix.

## Planning

Planning runs once, at the end of the `Compiler` constructor, before any class is written. For
every defined function that is not in the interpreted set and has more than 200 compiler
instructions, `MethodSplitter.plan`:

1. Emits the function with the normal emitters into a throwaway ASM `MethodNode`, recording the
   bytecode size after each instruction (`CodeSizeCounter`, which over-estimates).
2. Stops if the total is within the limit: `endive.hugeMethodLimit` (default 8000), capped at
   48000.
3. Skips functions it cannot handle: too many parameters for normal argument passing, or
   reference-typed parameters, results or locals.
4. Tries a direct split first, then a trampoline split. If neither finds a plan, the function
   compiles to one method as usual.

Planning measures the full size before the class writer's 64 KB check runs, so a function that
would fall back to the interpreter is compiled in pieces when a plan exists. `compileFunction`
handles interpreted functions before it looks for a plan, so they are never split.

Positions are indices into the compiler's lowered instruction list. Operand-stack contents at each
label come from ASM's `Analyzer` on the measured `MethodNode`.

## Direct split

A cut goes right after a label, and only where:

- the operand stack is empty;
- the label is not a loop header (the target of a backward branch);
- no branch crosses it: every branch's source and target are in the same chunk.

A greedy pass packs chunks up to the size limit. For a `br_table` with at least 8 distinct
targets, each target that is safe on its own becomes a mandatory cut, exempt from that switch's
own branches; a final check rejects the plan if any target lands anywhere other than a chunk start.

Emission (`emitChunkedFunction`): chunk 0 is the function's own method, and later chunks are
`<name>_chunk_<k>(params..., Memory, Instance, locals...)`, matching `Context`'s normal slot
layout. Each chunk ends by calling the next one and returning its result. A switch target in
another chunk goes through a stub that does the same.

The direct split is not tried for functions containing `try_table`, or whose locals would exceed
the JVM's 255 argument slots. Since no branch may cross a cut, it can never cut inside a loop.

## Trampoline split

The general case: branches between chunks become "set the next chunk and return to a driver", so
loops can span chunks.

All locals live in a `long[]` frame: the locals, then a PC slot, then up to 8 slots for spilled
operand-stack values. The function's own method keeps its signature, copies the parameters into
the frame and runs a driver loop (`emitTrampolineFunction`); each chunk is a separate method
(`emitTrampolineChunk`):

```
f(params..., Memory m, Instance i) {
  long[] frame = new long[locals + 1 + maxSpill];   // params copied in
  for (;;) {
    checkInterruption();
    switch ((int) frame[PC]) {
      case k: r = f_tc_k(frame, m, i);
              if (frame[PC] == DONE) return r;
              continue;
      ...
    }
  }
}

f_tc_k(long[] frame, Memory m, Instance i) {
  frame[PC] = DONE;
  // load the locals this chunk reads or writes; reload spilled stack values
  // ...the chunk's instructions; branches within the chunk are plain jumps...
  // branch to another chunk: spill the stack into the frame, pc = target, goto exit
exit:
  // store the locals this chunk writes; frame[PC] = pc; return a dummy value
}
```

Invariants the emission relies on:

- Every branch that leaves a chunk targets the first instruction of another chunk.
- Between chunks, the frame is the only copy of the locals. A chunk loads every local it reads or
  writes, so a store on only some paths still writes back a defined value.
- The operand stack at a chunk start is spilled by every transition into it and reloaded in the
  same order, from the types recorded in `Plan.entryStacks`.
- Every exit leaves the operand stack empty, so all transitions share one epilogue.
- The PC is `DONE` unless a transition set it, so a WASM `return` stays a plain return.
- The driver checks for interruption on every dispatch, which covers loop back-edges that now
  cross chunks; back-edges within a chunk keep their usual check.

Planning (`planTrampolineSplit`, `planTrampolineStarts`):

- **Possible starts:** position 0, plus any label whose operand stack holds at most 8 values and no
  references (references cannot be stored in a `long[]`).
- **`try_table`:** the protected range, its handler and the instruction that declares it must stay
  in one chunk, so no start may fall inside that region.
- **Seeding:** starts are first chosen by size alone.
- **Repair, repeated until stable:** every branch whose target lies in another chunk turns that
  target into a start. Each chunk is then costed exactly, as its bytecode plus the loads and stores
  of the locals it touches (`trampolineChunkOverhead`), and a chunk still over the limit is split
  at the last start that fits.
- **Retries:** a failed plan is retried with budgets 25% and 50% smaller, which places the cuts
  differently.
- **Limit:** at most 4096 chunks.

## Costs and limitations

- **Compile time:** every function over 200 instructions is emitted an extra time to measure it,
  and once more if it reaches the trampoline.
- **Run time:** a branch between trampoline chunks costs a return to the driver, a `tableswitch`,
  a call, and loading and storing the touched locals.
- **The driver is not split.** It costs about 28 bytes per chunk, so with more than about 285
  chunks the driver itself is over 8000 bytes and is not JIT-compiled. With more than about 2300
  chunks it exceeds 64 KB, and the function falls back to the interpreter.
- **Some functions cannot be cut:** a long straight run of code with no branch target in it
  (for example the synthetic function in `MethodTooLargeTest`), or code whose branch targets carry
  references, or more than 8 values, on the operand stack.
- **Constants:** the thresholds above (200 instructions, 8 switch targets, 8 spilled values, 4096
  chunks) are not configurable.
