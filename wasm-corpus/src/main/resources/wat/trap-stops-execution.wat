;; A trap in a callee has to abandon the caller too. Each export below leaves an
;; observable mark after the call that traps, so if execution carried on past the
;; trap the mark is still there once the exception surfaces.
(module
  (memory (export "mem") 1)

  (func $trapper (result i32)
    (i32.div_s (i32.const 1) (i32.const 0)))

  ;; mem[0] stays 0 unless execution continued past the trap
  (func (export "storeAfterTrap")
    (drop (call $trapper))
    (i32.store (i32.const 0) (i32.const 42)))

  ;; mem[4] counts loop iterations that ran after the trap
  (func (export "loopAfterTrap")
    (drop (call $trapper))
    (i32.store (i32.const 4) (i32.const 7)))
)
