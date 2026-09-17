(module
  (tag $e (param i32))
  (func $throw (param i32) (throw $e (local.get 0)))

  (func $capture (param $val i32) (result (ref exn))
    (block $h (result (ref exn))
      (try_table (catch_all_ref $h) (call $throw (local.get $val)))
      (unreachable)))

  ;; ref.test against the exn hierarchy
  (func (export "test-exn-nonnull") (result i32)
    (ref.test (ref exn) (call $capture (i32.const 7))))
  (func (export "test-exn-null") (result i32)
    (ref.test (ref exn) (ref.null exn)))
  (func (export "test-nullable-exn-null") (result i32)
    (ref.test (ref null exn) (ref.null exn)))
  (func (export "test-noexn-nonnull") (result i32)
    (ref.test (ref null noexn) (call $capture (i32.const 7))))

  ;; br_table carrying an exnref through two different label depths
  (func (export "br-table") (param $pick i32) (result i32)
    (local $a exnref)
    (local.set $a (call $capture (i32.const 5)))
    (block $outer (result i32)
      (block $l1 (result exnref)
        (block $l0 (result exnref)
          (local.get $a)
          (br_table $l0 $l1 (local.get $pick)))
        (drop)
        (br $outer (i32.const 10)))
      (drop)
      (i32.const 20)))
)
