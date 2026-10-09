;; A memory.init that traps must stop execution.
(module
  (memory 1)
  (data $d "hello")

  ;; 6 bytes from a 5 byte segment: traps before the store
  (func (export "initOutOfBoundsThenStore")
    (memory.init $d (i32.const 0) (i32.const 0) (i32.const 6))
    (i32.store (i32.const 100) (i32.const 1)))

  ;; source offset 1 of a dropped (empty) segment: traps even with length 0
  (func (export "dropThenInitPastEnd")
    (data.drop $d)
    (memory.init $d (i32.const 0) (i32.const 1) (i32.const 0)))

  ;; offset 0 and length 0 of a dropped segment: allowed
  (func (export "dropThenInitNothing")
    (data.drop $d)
    (memory.init $d (i32.const 0) (i32.const 0) (i32.const 0)))

  (func (export "read") (param i32) (result i32)
    (i32.load (local.get 0))))
