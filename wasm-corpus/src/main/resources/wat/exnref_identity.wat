(module
  ;; Each `throw` creates a distinct exception. Two live exceptions sharing a tag
  ;; must not alias, whether they are held in locals, globals or a table.

  (tag $e (param i32))
  (func $throw (param i32) (throw $e (local.get 0)))

  (global $ga (mut exnref) (ref.null exn))
  (global $gb (mut exnref) (ref.null exn))
  (table $t 2 exnref)

  ;; catch one exception and hand it back
  (func $capture (param $val i32) (result (ref exn))
    (block $h (result (ref exn))
      (try_table (catch_all_ref $h) (call $throw (local.get $val)))
      (unreachable)
    )
  )

  ;; rethrow the given exnref and report the payload the handler observes
  (func $payload_of (param $x exnref) (result i32)
    (block $h (result i32 (ref exn))
      (try_table (result i32) (catch_ref $e $h)
        (local.get $x)
        (throw_ref)
      )
      (return)
    )
    (drop)
  )

  ;; two exceptions on one tag, held in locals
  (func (export "locals") (result i32)
    (local $a exnref)
    (local $b exnref)
    (local.set $a (call $capture (i32.const 1)))
    (local.set $b (call $capture (i32.const 2)))
    (i32.add
      (i32.mul (call $payload_of (local.get $a)) (i32.const 10))
      (call $payload_of (local.get $b)))
  )

  ;; the same, held in two globals
  (func (export "globals") (result i32)
    (global.set $ga (call $capture (i32.const 1)))
    (global.set $gb (call $capture (i32.const 2)))
    (i32.add
      (i32.mul (call $payload_of (global.get $ga)) (i32.const 10))
      (call $payload_of (global.get $gb)))
  )

  ;; the same, held in a table
  (func (export "table") (result i32)
    (table.set $t (i32.const 0) (call $capture (i32.const 1)))
    (table.set $t (i32.const 1) (call $capture (i32.const 2)))
    (i32.add
      (i32.mul (call $payload_of (table.get $t (i32.const 0))) (i32.const 10))
      (call $payload_of (table.get $t (i32.const 1))))
  )

  ;; select between two live exceptions -- exercises the un-normalised
  ;; VEC_VALUE_TYPE operand path in the validator/interpreter
  (func (export "select") (param $pick i32) (result i32)
    (local $a exnref)
    (local $b exnref)
    (local.set $a (call $capture (i32.const 1)))
    (local.set $b (call $capture (i32.const 2)))
    (call $payload_of
      (select (result exnref) (local.get $a) (local.get $b) (local.get $pick)))
  )
)
