(module
  ;; An interpreted function reaching another one through a tail call, which calls a third.
  (func $interpreted (export "interpreted") (param i32) (result i32)
    local.get 0
    return_call $tail_called)
  (func $tail_called (param i32) (result i32)
    local.get 0
    call $compiled)
  (func $compiled (param i32) (result i32)
    local.get 0
    i32.const 1
    i32.add))
