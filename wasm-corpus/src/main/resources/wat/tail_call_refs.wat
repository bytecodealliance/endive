(module
  ;; Tail calls that resolve through the reference-typed call path.

  (func $count (export "count") (param externref i32) (result i32)
    (if (result i32) (i32.eqz (local.get 1))
      (then (i32.const 42))
      (else (return_call $count (local.get 0) (i32.sub (local.get 1) (i32.const 1))))))

  (func $chain (param i32) (result i31ref)
    (if (result i31ref) (i32.eqz (local.get 0))
      (then (ref.i31 (i32.const 42)))
      (else (return_call $chain (i32.sub (local.get 0) (i32.const 1))))))
  (func (export "chain") (result i32)
    (i31.get_s (ref.as_non_null (call $chain (i32.const 3)))))

  (func (export "plain") (result i32) (i32.const 7))
)
