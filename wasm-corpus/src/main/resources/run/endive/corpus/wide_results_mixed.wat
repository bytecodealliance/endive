;; every numeric type in one wide result tuple; floats travel as their raw bits
(module
  (func $leaf (result i32 f32 f64#foreach ($v in $i64s) i64#{end})
    i32.const -7
    f32.const -1.5
    f64.const nan:0x4000000000001
#foreach ($v in $i64s)
    i64.const $v
#end
  )

  (func (export "run") (result i32 f32 f64#foreach ($v in $i64s) i64#{end})
    call $leaf)
)
