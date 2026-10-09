;; A host import grows the memory, and the guest then uses the new page.
(module
  (import "env" "memory" (memory 1 2))
  (import "env" "grow" (func $grow))

  (func (export "growThenRead") (param $addr i32) (result i32)
    (call $grow)
    (i32.load (local.get $addr)))

  (func (export "growThenSize") (result i32)
    (call $grow)
    (memory.size)))
