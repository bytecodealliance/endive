;; A mutable global the module changes, to check each instance keeps its own.
(module
  (global $count (export "count") (mut i32) (i32.const 0))

  (func (export "increment") (result i32)
    (global.set $count (i32.add (global.get $count) (i32.const 1)))
    (global.get $count))
)
