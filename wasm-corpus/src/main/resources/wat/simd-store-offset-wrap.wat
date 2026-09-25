(module
  (memory 1)

  ;; address + offset >= 2^32 must trap, not wrap to a low address
  (func (export "v128_store") (param $addr i32)
    (v128.store offset=1 (local.get $addr) (v128.const i64x2 -1 -1)))

  (func (export "v128_store8_lane") (param $addr i32)
    (v128.store8_lane offset=1 0 (local.get $addr) (v128.const i64x2 -1 -1)))

  (func (export "v128_store_max_offset") (param $addr i32)
    (v128.store offset=0xffffffff (local.get $addr) (v128.const i64x2 -1 -1)))

  (func (export "v128_store8_lane_max_offset") (param $addr i32)
    (v128.store8_lane offset=0xffffffff 0 (local.get $addr) (v128.const i64x2 -1 -1)))
)
