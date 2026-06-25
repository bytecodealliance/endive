(module
  ;; Bug 2: null GC ref survives control transfer (block boundary)
  (type $s (struct (field i32)))

  (func (export "null_ref_through_block") (result i32)
    (local $r (ref null $s))
    (local.set $r
      (block (result (ref null $s))
        ref.null $s
      )
    )
    (ref.is_null (local.get $r))
  )

  ;; Bug 2 variant: null GC ref survives nested blocks
  (func (export "null_ref_nested_blocks") (result i32)
    (local $r (ref null $s))
    (local.set $r
      (block (result (ref null $s))
        (block (result (ref null $s))
          ref.null $s
        )
      )
    )
    (ref.is_null (local.get $r))
  )

  ;; Bug 2: non-null GC ref through block boundary (regression check)
  (func (export "nonnull_ref_through_block") (result i32)
    (local $r (ref null $s))
    (local.set $r
      (block (result (ref null $s))
        (struct.new $s (i32.const 42))
      )
    )
    (struct.get $s 0 (local.get $r))
  )

  ;; Bug 7: null GC ref through exception catch
  (tag $e (param (ref null $s)))

  (func (export "null_ref_exception") (result i32)
    (local $r (ref null $s))
    (block $b (result (ref null $s))
      (try_table (catch $e $b)
        (throw $e (ref.null $s))
      )
      (unreachable)
    )
    (local.set $r)
    (ref.is_null (local.get $r))
  )

  ;; Bug 7: non-null GC ref through exception (regression check)
  (func (export "nonnull_ref_exception") (result i32)
    (block $b (result (ref null $s))
      (try_table (catch $e $b)
        (throw $e (struct.new $s (i32.const 99)))
      )
      (unreachable)
    )
    (struct.get $s 0)
  )

  ;; Bug 8: table.fill with valid bounds
  (table $t 10 (ref null $s))

  (func (export "table_fill_bounds") (result i32)
    ;; Fill first 3 entries, should succeed
    (table.fill $t (i32.const 0) (ref.null $s) (i32.const 3))
    (i32.const 1) ;; success
  )
)
