(module
  (type $pad0 (func (param i32 i32) (result i32)))
  (type $pad1 (func (param i64) (result i64)))
  (type $target (func (result i32)))

  (table (export "shared-table") 10 funcref)

  (func (export "call-other") (result i32)
    i32.const 0
    call_indirect (type $target))
)
