;; $leaf needs splitting and keeps v128 values live across its chunks
(module
  (func $leaf (param $p i32) (param $x v128) (result v128) (local $acc v128)
    (local.set $acc (local.get $x))
#if ($inLoop)
    (loop $l
#end
#foreach ($block in $blocks)
    (block (br_if 0 (local.get $p))
#foreach ($v in $statements)
      (local.set $acc (i32x4.add (local.get $acc) (i32x4.splat (i32.const $v))))
#end
      (local.set $acc (i64x2.add (local.get $acc) (local.get $x)))
    )
#end
#if ($inLoop)
    (br_if $l (local.get $p)))
#end
    (local.get $acc))

  (func (export "run") (param i32 i64 i64) (result i64 i64)
    (local $r v128)
    (local.set $r
      (call $leaf
        (local.get 0)
        (i64x2.replace_lane 1 (i64x2.splat (local.get 1)) (local.get 2))))
    (i64x2.extract_lane 0 (local.get $r))
    (i64x2.extract_lane 1 (local.get $r)))
)
