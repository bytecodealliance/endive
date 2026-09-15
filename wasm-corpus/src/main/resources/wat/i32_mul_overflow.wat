(module
    ;; mul(a, b) returns a*b wrapped to 32 bits
    (func (export "mul") (param $a i32) (param $b i32) (result i32)
        (i32.mul (local.get $a) (local.get $b))
    )
    ;; mul_if(a, b) returns 1 if the wrapped a*b is non-zero, 0 otherwise
    (func (export "mul_if") (param $a i32) (param $b i32) (result i32)
        (if (result i32) (i32.mul (local.get $a) (local.get $b))
            (then (i32.const 1))
            (else (i32.const 0))
        )
    )
)
