;; Interruption raised by a host call must be observed before dispatching a pending tail call.
(module
  (import "env" "interrupt" (func $interrupt))
  (import "env" "target" (func $target (result i64)))
  (import "env" "target_ref" (func $target_ref (result externref)))

  (func $tail (export "direct") (result i64)
    (call $interrupt)
    (return_call $target))
  (func (export "nested") (result i64) (call $tail))

  (func $tail_ref (export "direct_ref") (result externref)
    (call $interrupt)
    (return_call $target_ref))
  (func (export "nested_ref") (result externref) (call $tail_ref))
  (func (export "value") (result i64) (i64.const 42)))
