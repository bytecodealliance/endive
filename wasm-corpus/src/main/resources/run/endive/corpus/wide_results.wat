;; $leaf returns i32 then i64 results, which each export gets in a different way
(module
  (type $t (func (result#foreach ($v in $i32s) i32#{end}#foreach ($v in $i64s) i64#{end})))
  (table funcref (elem $leaf))

  (func $leaf (type $t)
#foreach ($v in $i32s)
    i32.const $v
#end
#foreach ($v in $i64s)
    i64.const $v
#end
  )

  (func (export "call") (type $t)
    call $leaf)

  (func (export "call_indirect") (type $t)
    i32.const 0
    call_indirect (type $t))

  (func (export "call_ref") (type $t)
    ref.func $leaf
    call_ref $t)

  (func (export "return_call") (type $t)
    return_call $leaf)
)
