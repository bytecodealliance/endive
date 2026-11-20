;; v128 values across calls, results, globals, exceptions and branches; see SimdBoundaryTest
(module
  (type $binop (func (param v128 v128) (result v128)))
  (import "env" "host_add" (func $host_add (type $binop)))
  (tag $vtag (param v128 i32))
  (global $g (mut v128) (v128.const i64x2 0 0))
  (table 2 funcref)
  (elem (i32.const 0) func $add $sub)

  (func $add (type $binop)
    (i64x2.add (local.get 0) (local.get 1)))

  (func $sub (type $binop)
    (i64x2.sub (local.get 0) (local.get 1)))

  (func $pair (param v128) (result v128 i32 v128)
    (local.get 0)
    (i32.const 3)
    (i64x2.add (local.get 0) (local.get 0)))

  (func $pair_ref (param v128) (result v128 anyref)
    (local.get 0)
    (ref.i31 (i32.const 5)))

  ;; the v128 of two i64 words
  (func $v (param i64 i64) (result v128)
    (i64x2.replace_lane 1 (i64x2.splat (local.get 0)) (local.get 1)))

  (func $thrower (param v128)
    (throw $vtag (local.get 0) (i32.const 7)))

  ;; 3 * (a, b): $add doubles, the host doubles again, call_indirect subtracts (a, b)
  (func (export "calls") (param i64 i64) (result i64 i64)
    (local $x v128)
    (local.set $x (call $v (local.get 0) (local.get 1)))
    (local.set $x (call $add (local.get $x) (local.get $x)))
    (local.set $x (call $host_add (local.get $x) (local.get $x)))
    (local.set $x
      (call_indirect (type $binop)
        (local.get $x) (call $v (local.get 0) (local.get 1)) (i32.const 1)))
    (global.set $g (local.get $x))
    (i64x2.extract_lane 0 (global.get $g))
    (i64x2.extract_lane 1 (global.get $g)))

  ;; (3a + 8, 3b + 3): $pair gives x, 3, 2x and $pair_ref the i31 5
  (func (export "multi") (param i64 i64) (result i64 i64)
    (local $a v128) (local $b v128) (local $n i32)
    (call $pair (call $v (local.get 0) (local.get 1)))
    (local.set $b)
    (local.set $n)
    (local.set $a)
    (local.set $a
      (i64x2.add
        (i64x2.add (local.get $a) (local.get $b))
        (i64x2.splat (i64.extend_i32_u (local.get $n)))))
    (call $pair_ref (local.get $a))
    (local.set $n (i31.get_s (ref.cast (ref i31))))
    (local.set $a)
    (i64x2.extract_lane 0
      (i64x2.add (local.get $a) (i64x2.replace_lane 1 (i64x2.splat (i64.const 5)) (i64.const 0))))
    (i64x2.extract_lane 1 (local.get $a)))

  ;; (a, b, 7): the payload of a tag thrown by $thrower
  (func (export "throw_catch") (param i64 i64) (result i64 i64 i32)
    (local $v v128) (local $n i32)
    (block $h (result v128 i32)
      (try_table (catch $vtag $h)
        (call $thrower (call $v (local.get 0) (local.get 1))))
      (unreachable))
    (local.set $n)
    (local.set $v)
    (i64x2.extract_lane 0 (local.get $v))
    (i64x2.extract_lane 1 (local.get $v))
    (local.get $n))

  ;; (b, a) for sel 0, (a + 100, b + 100) otherwise
  (func (export "select_br") (param i64 i64 i32) (result i64 i64)
    (local $r v128)
    (drop (local.tee $r (call $v (local.get 0) (local.get 1))))
    (local.set $r
      (block $b0 (result v128)
        (block $b1 (result v128)
          (select
            (local.get $r)
            (call $v (local.get 1) (local.get 0))
            (local.get 2))
          (br_table $b0 $b1 (local.get 2)))
        (i64x2.add (v128.const i64x2 100 100))))
    (i64x2.extract_lane 0 (local.get $r))
    (i64x2.extract_lane 1 (local.get $r)))

  (type $vfun (func (param v128) (result v128)))
  (elem declare func $double)

  (func $double (type $vfun)
    (i64x2.add (local.get 0) (local.get 0)))

  (func $tail (type $vfun)
    (return_call $double (local.get 0)))

  (func $tail_indirect (type $vfun)
    (return_call_indirect (type $binop) (local.get 0) (local.get 0) (i32.const 0)))

  (func $tail_ref (type $vfun)
    (return_call_ref $vfun (local.get 0) (ref.func $double)))

  ;; 16 * (a, b): three tail calls and a call_ref, each doubling
  (func (export "tail_calls") (param i64 i64) (result i64 i64)
    (local $x v128)
    (local.set $x (call $tail (call $v (local.get 0) (local.get 1))))
    (local.set $x (call $tail_indirect (local.get $x)))
    (local.set $x (call $tail_ref (local.get $x)))
    (local.set $x (call_ref $vfun (local.get $x) (ref.func $double)))
    (i64x2.extract_lane 0 (local.get $x))
    (i64x2.extract_lane 1 (local.get $x)))
)
