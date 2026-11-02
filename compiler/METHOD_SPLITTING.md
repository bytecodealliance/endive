# Method splitting

Endive compiles each WASM function to one JVM method. HotSpot never JIT-compiles a method
larger than 8000 bytes of bytecode (`-XX:HugeMethodLimit`, enforced by
`-XX:+DontCompileHugeMethods`), and the JVM rejects methods over 64 KB, which Endive sends to
its interpreter. Large functions, often a language runtime's main loop (QuickJS's
`JS_CallInternal`, CPython's `_PyEval_EvalFrameDefault`), therefore run slowly however hot they
are.

Method splitting compiles such a function into several methods that are each under 8000 bytes.
It is experimental and disabled by default.

## Enabling

```java
MachineFactoryCompiler.builder(module).withMethodSplitting(true).compile();
```

`Compiler.Builder.withMethodSplitting(boolean)` is the same switch on the internal compiler. With
the flag off, no planning runs and the generated classes are identical to those without this
feature. With it on, the compile cache stores results under a separate key.

## When a function is split

Planning runs once, in the `Compiler` constructor (`Compiler.planMethodSplits`), before any class
is written. For every defined function that is not in the interpreted set and has more than 200
compiler instructions, `MethodSplitter.plan`:

1. Emits the function with the normal emitters into a throwaway ASM `MethodNode`, recording the
   bytecode size after each instruction (`CodeSizeCounter`, which over-estimates).
2. Stops if the total is within the limit: `endive.hugeMethodLimit` (default 8000), capped at
   48000.
3. Skips functions it cannot handle: too many parameters for normal argument passing, or
   reference-typed parameters, results or locals.
4. Tries a direct split first, then a trampoline split. If neither finds a safe plan, the
   function compiles to one method as before, without any message.

Planning measures the full size before the class writer's 64 KB check runs. So a function that
would otherwise fall back to the interpreter is compiled in pieces when it can be split.

All positions below are indices into the compiler's lowered instruction list. Operand-stack
contents at each label come from ASM's `Analyzer` on the measured `MethodNode`.

## Direct split

A cut goes after a label and is allowed only if:

- the operand stack is empty there;
- the label is not a loop header (the target of a backward branch);
- no branch crosses it: for every branch, the source and target end up in the same chunk.

A greedy pass then packs chunks up to the size limit. For a `br_table` with at least 8 distinct
targets, each target that is safe on its own becomes a mandatory cut; a final check rejects the
plan if any switch target lands anywhere other than the start of a chunk.

Generated code: chunk 0 is the function's own method, and later chunks are
`<name>_chunk_<k>(params..., Memory, Instance, locals...)`. Each chunk ends by calling the next
one and returning its result. A switch target in another chunk becomes a small stub that does the
same.

The direct split is not used for functions containing `try_table`, or whose locals would exceed
the JVM's 255 argument slots. Since nothing may cross a cut, it can never cut inside a loop.

## Trampoline split

This is the general case, used when the direct split finds no plan. It handles loops because
control flow between chunks no longer uses jumps.

All locals live in a `long[]` frame with this layout: the locals, then a PC slot, then up to 8
slots for spilled operand-stack values. The function's own method keeps its signature, copies the
parameters into the frame and runs a driver loop:

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

A WASM `return` inside a chunk stays a plain return. The PC is still `DONE`, so the driver
returns the value. The driver checks for interruption on every dispatch, which covers loop
back-edges that now cross chunks; back-edges within a chunk keep their usual check.

Planning:

- **Chunk starts:** position 0, plus any label whose operand stack holds at most 8 values and no
  references (references cannot be stored in a `long[]`).
- **`try_table`:** the protected range, its handler and the instruction that declares it must
  stay in one chunk, so no start may fall inside that region.
- **Seeding:** starts are first chosen by size alone.
- **Repair, repeated until stable:** every branch whose target lies in another chunk turns that
  target into a chunk start. Each chunk is then costed exactly, as its bytecode plus the cost of
  loading and storing the locals it touches, and any chunk still over the limit is split at the
  last start that fits.
- **Retries:** if planning fails, it is retried with budgets 25% and 50% smaller, which places the
  cuts differently.
- **Limit:** at most 4096 chunks.

## Code map

- `MethodSplitter.plan` and `MethodSplitter.emit`: all planning and emission. `Plan` holds either
  the direct cuts or the trampoline starts with their spilled-stack types.
- `Compiler.planMethodSplits`: runs the planner and prints one `Info:` line per split function,
  unless `InterpreterFallback.SILENT` is set.
- `Compiler.compileFunction`: emits a function through `MethodSplitter.emit` when it has a plan.
  Interpreted functions are handled first and never split.
- `Compiler.emitInstruction` and `Compiler.emitLocalsDefaultInit`: the normal emitters, shared
  with the splitter.
- `Context` with `forceFrameLayout`: the slot layout trampoline chunks use (frame, `Memory`,
  `Instance`, then locals).

## Costs and limitations

- **Compile time:** every function over 200 instructions is emitted an extra time to measure it,
  and once more if it reaches the trampoline. This adds about 0.5 to 1.2 s on modules such as
  QuickJS, Lua and SQLite.
- **Run time:** a branch between trampoline chunks costs a return to the driver, a `tableswitch`,
  a call, and loading and storing the touched locals.
- **The driver is not split.** It costs about 28 bytes per chunk, so with more than about 285
  chunks the driver itself is over 8000 bytes and is not JIT-compiled. With more than about 2300
  chunks it exceeds 64 KB, and the function falls back to the interpreter.
- **Some functions cannot be cut:** a long straight run of code with no branch target in it
  (for example the synthetic function in `MethodTooLargeTest`), or code whose branch targets carry
  references, or more than 8 values, on the operand stack.
- **Not tuned:** the limits above (200 instructions, 8 targets, 8 spilled values, 4096 chunks)
  are constants.
