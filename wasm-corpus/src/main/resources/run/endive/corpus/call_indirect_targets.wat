(module
  (type $t (func (param i64 i32) (result i64)))
  (table $functions.size() funcref)
  (elem (i32.const 0)#foreach ($func in $functions) $f$func#{end})

#foreach ($func in $functions)
  (func $f$func (type $t)
    local.get 0
    local.get 1
    i64.extend_i32_u
    i64.add
    i64.const $func
    i64.add
  )
#end

  (func (export "call") (param i32 i64 i32) (result i64)
    local.get 1
    local.get 2
    local.get 0
    call_indirect (type $t)
  )
)
