;; A host function that throws has to abandon the module the same way a trap
;; does. mem[0] stays 0 unless execution carried on after the exception.
(module
  (import "host" "boom" (func $boom))
  (memory (export "mem") 1)

  (func (export "callBoom")
    (call $boom)
    (i32.store (i32.const 0) (i32.const 42)))
)
