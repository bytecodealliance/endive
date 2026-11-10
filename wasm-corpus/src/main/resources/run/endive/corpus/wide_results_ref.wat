;; a wide result tuple holding the externref argument, which must keep its identity
(module
  (func $leaf (param externref) (result i32 externref#foreach ($v in $i64s) i64#{end})
    i32.const 5
    local.get 0
#foreach ($v in $i64s)
    i64.const $v
#end
  )

  (func (export "run") (param externref) (result i32 externref#foreach ($v in $i64s) i64#{end})
    local.get 0
    call $leaf)
)
