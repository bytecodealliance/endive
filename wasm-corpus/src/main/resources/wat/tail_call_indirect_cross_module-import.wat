(module
  ;; the table holds a function of another instance
  (type $t (func (result i32)))
  (import "test" "table" (table 1 funcref))
  (func $same_index (result i32) (i32.const 99))
  (func (export "call") (result i32) (call_indirect (type $t) (i32.const 0)))
  (func (export "tail") (result i32) (return_call_indirect (type $t) (i32.const 0))))
