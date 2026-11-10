;; $leaf is too large for one JVM method: split at its blocks, or through the trampoline when
;; the blocks sit in a loop
(module
  (func $leaf (param $p i32) (result i32#foreach ($v in $i64s) i64#{end}) (local $acc i64)
#if ($inLoop)
    (loop $l
#end
#foreach ($block in $blocks)
    (block (br_if 0 (local.get $p))
#foreach ($v in $statements)
      (local.set $acc (i64.add (local.get $acc) (i64.const $v)))
#end
    )
#end
#if ($inLoop)
    (br_if $l (local.get $p)))
#end
    i32.const 42
#foreach ($v in $i64s)
    (i64.add (local.get $acc) (i64.const $v))
#end
  )

  (func (export "run") (param i32) (result i32#foreach ($v in $i64s) i64#{end})
    local.get 0
    call $leaf)
)
