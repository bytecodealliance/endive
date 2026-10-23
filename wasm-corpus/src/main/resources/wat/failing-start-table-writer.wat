;; Puts a function that reads an imported memory in slot 1 of an imported
;; table, then traps while starting, so building it fails.
(module
  (import "test" "shared-table" (table 5 funcref))
  (import "env" "memory" (memory 1))

  (func $other (result i32)
    i32.const 0
    i32.load)

  (elem (i32.const 1) func $other)

  (func $start
    unreachable)

  (start $start)
)
