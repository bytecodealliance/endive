(module
  (memory 1)

  ;; address + offset >= 2^32 must trap, not wrap to a low address
  (func (export "i32_store8") (param $addr i32)
    (i32.store8 offset=1 (local.get $addr) (i32.const 42)))

  (func (export "i64_store8") (param $addr i32)
    (i64.store8 offset=1 (local.get $addr) (i64.const 42)))

  (func (export "i64_store32") (param $addr i32)
    (i64.store32 offset=1 (local.get $addr) (i64.const 42)))

  (func (export "i32_store8_max_offset") (param $addr i32)
    (i32.store8 offset=0xffffffff (local.get $addr) (i32.const 42)))
)
