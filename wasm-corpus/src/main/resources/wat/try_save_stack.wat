(module
  ;; Test that values below try_table are preserved after catch.
  ;; JVM exception handlers clear the operand stack, but WASM
  ;; try_table semantics preserve values below the try scope.

  (tag $e (param i32))

  (func $do_throw (param $val i32)
    (throw $e (local.get $val))
  )

  ;; Push a value, enter try_table, throw, catch — the value below must survive.
  (func (export "value-below-try") (result i32)
    (i32.const 10)               ;; this value is below the try scope
    (block $h (result i32)
      (try_table (catch $e $h)
        (call $do_throw (i32.const 32))
      )
      (unreachable)
    )
    (i32.add)                    ;; 10 + 32 = 42
  )

  ;; Two values below the try scope
  (func (export "two-values-below-try") (result i32)
    (i32.const 100)              ;; bottom value
    (i32.const 200)              ;; second value
    (block $h (result i32)
      (try_table (catch $e $h)
        (call $do_throw (i32.const 5))
      )
      (unreachable)
    )
    (i32.add)                    ;; 200 + 5 = 205
    (i32.add)                    ;; 100 + 205 = 305
  )

  ;; Catch branches to an outer label, dropping a value below the try but above the target.
  (func (export "catch-drops-value-above-target") (result i32)
    (block $t (result i32)
      (i32.const 99)             ;; above $t -> dropped when the catch branches
      (try_table (catch $e $t)
        (call $do_throw (i32.const 7))
      )
      (unreachable)
    )                            ;; $t yields the caught 7
  )

  ;; Like above, but a value below the target label survives the unwind.
  (func (export "catch-keeps-value-below-target") (result i32)
    (i32.const 1000)             ;; below $t -> survives
    (block $t (result i32)
      (i32.const 99)             ;; above $t -> dropped
      (try_table (catch $e $t)
        (call $do_throw (i32.const 7))
      )
      (unreachable)
    )
    (i32.add)                    ;; 1000 + 7 = 1007
  )

  ;; Catch branches backward to a loop.
  (func (export "catch-backward-to-loop") (result i32)
    (local $count i32)
    (local $result i32)
    (i32.const 0)
    (loop $L (param i32) (result i32)
      (local.set $result)
      (local.get $count)
      (if (then
        (local.get $result)
        (return)
      ))
      (local.get $count)
      (i32.const 1)
      (i32.add)
      (local.set $count)
      (try_table (catch $e $L)
        (call $do_throw (i32.const 42))
      )
      (unreachable)
    )
  )

  ;; Catch branches backward to a loop, dropping an intermediate value.
  (func (export "catch-backward-to-loop-drop") (result i32)
    (local $count i32)
    (local $result i32)
    (i32.const 0)
    (loop $L (param i32) (result i32)
      (local.set $result)
      (local.get $count)
      (if (then
        (local.get $result)
        (return)
      ))
      (local.get $count)
      (i32.const 1)
      (i32.add)
      (local.set $count)
      (i32.const 999)                ;; above $L -> must be dropped by catch unwind
      (try_table (catch $e $L)
        (call $do_throw (i32.const 42))
      )
      (unreachable)
    )
  )

  ;; Nested try_table with values below both levels
  (func (export "nested-try-values") (result i32)
    (i32.const 1)                ;; below outer try
    (block $outer (result i32)
      (try_table (catch $e $outer)
        (i32.const 2)            ;; below inner try
        (block $inner (result i32)
          (try_table (catch $e $inner)
            (call $do_throw (i32.const 3))
          )
          (unreachable)
        )
        (i32.add)                ;; 2 + 3 = 5
        (call $do_throw)         ;; throw 5 to outer
      )
      (unreachable)
    )
    (i32.add)                    ;; 1 + 5 = 6
  )
)
