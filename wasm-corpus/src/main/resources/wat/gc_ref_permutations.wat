(module
  ;; Exercises every meaningful permutation of primitive (long) and GC ref (Object)
  ;; in both parameter and return positions, for exports and imports.

  (type $point (struct (field $x i32) (field $y i32)))

  ;; ============================================================
  ;; IMPORTS — param / return permutations
  ;; ============================================================

  ;; long → ref (import returns a GC ref)
  (import "env" "make_point" (func $make_point (param i32 i32) (result (ref $point))))

  ;; ref → long (import takes a GC ref)
  (import "env" "get_x" (func $get_x (param (ref null $point)) (result i32)))

  ;; (ref, ref) → long (import takes multiple refs)
  (import "env" "sum_x" (func $sum_x (param (ref null $point) (ref null $point)) (result i32)))

  ;; (long, ref) → long (import takes mixed params)
  (import "env" "add_to_x" (func $add_to_x (param i32 (ref null $point)) (result i32)))

  ;; ref → ref (import takes and returns ref)
  (import "env" "swap_xy" (func $swap_xy (param (ref null $point)) (result (ref $point))))

  ;; ============================================================
  ;; EXPORTS — param / return permutations
  ;; ============================================================

  ;; --- Single param, single return ---

  ;; long → long (baseline)
  (func (export "long_to_long") (param i32) (result i32)
    (local.get 0))

  ;; ref → long
  (func (export "ref_to_long") (param (ref null $point)) (result i32)
    (struct.get $point $x (local.get 0)))

  ;; long → ref
  (func (export "long_to_ref") (param i32) (result (ref $point))
    (struct.new $point (local.get 0) (local.get 0)))

  ;; ref → ref
  (func (export "ref_to_ref") (param (ref null $point)) (result (ref $point))
    (struct.new $point
      (struct.get $point $y (local.get 0))
      (struct.get $point $x (local.get 0))))

  ;; --- Multi-param, single return ---

  ;; (long, ref) → long
  (func (export "long_ref_to_long") (param i32 (ref null $point)) (result i32)
    (i32.add (local.get 0) (struct.get $point $x (local.get 1))))

  ;; (ref, long) → long
  (func (export "ref_long_to_long") (param (ref null $point) i32) (result i32)
    (i32.add (struct.get $point $x (local.get 0)) (local.get 1)))

  ;; (ref, ref) → long
  (func (export "ref_ref_to_long") (param (ref null $point) (ref null $point)) (result i32)
    (i32.add
      (struct.get $point $x (local.get 0))
      (struct.get $point $x (local.get 1))))

  ;; (long, ref) → ref
  (func (export "long_ref_to_ref") (param i32 (ref null $point)) (result (ref $point))
    (struct.new $point
      (local.get 0)
      (struct.get $point $y (local.get 1))))

  ;; (ref, long, ref) → long  (interleaved)
  (func (export "ref_long_ref_to_long") (param (ref null $point) i32 (ref null $point)) (result i32)
    (i32.add
      (i32.add
        (struct.get $point $x (local.get 0))
        (local.get 1))
      (struct.get $point $x (local.get 2))))

  ;; --- Single param, multi-return ---

  ;; long → (long, ref)
  (func (export "long_to_long_ref") (param i32) (result i32 (ref $point))
    (local.get 0)
    (struct.new $point (local.get 0) (local.get 0)))

  ;; long → (ref, long)
  (func (export "long_to_ref_long") (param i32) (result (ref $point) i32)
    (struct.new $point (local.get 0) (local.get 0))
    (local.get 0))

  ;; long → (ref, ref)
  (func (export "long_to_ref_ref") (param i32) (result (ref $point) (ref $point))
    (struct.new $point (local.get 0) (i32.const 0))
    (struct.new $point (i32.const 0) (local.get 0)))

  ;; --- void return ---

  ;; ref → void
  (func (export "ref_to_void") (param (ref null $point))
    (drop (struct.get $point $x (local.get 0))))

  ;; --- No params ---

  ;; void → ref
  (func (export "void_to_ref") (result (ref $point))
    (struct.new $point (i32.const 100) (i32.const 200)))

  ;; ============================================================
  ;; EXPORT wrappers that exercise the imports
  ;; ============================================================

  ;; Tests make_point import: long params → ref return
  (func (export "test_make_point") (param i32 i32) (result i32)
    (struct.get $point $x (call $make_point (local.get 0) (local.get 1))))

  ;; Tests get_x import: ref param → long return
  (func (export "test_get_x") (param i32 i32) (result i32)
    (call $get_x (struct.new $point (local.get 0) (local.get 1))))

  ;; Tests sum_x import: (ref, ref) params
  (func (export "test_sum_x") (param i32 i32) (result i32)
    (call $sum_x
      (struct.new $point (local.get 0) (i32.const 0))
      (struct.new $point (local.get 1) (i32.const 0))))

  ;; Tests add_to_x import: (long, ref) mixed params
  (func (export "test_add_to_x") (param i32 i32) (result i32)
    (call $add_to_x
      (local.get 0)
      (struct.new $point (local.get 1) (i32.const 0))))

  ;; Tests swap_xy import: ref → ref
  (func (export "test_swap_xy") (param i32 i32) (result i32)
    (struct.get $point $x
      (call $swap_xy (struct.new $point (local.get 0) (local.get 1)))))

  ;; ============================================================
  ;; Helpers
  ;; ============================================================

  (func (export "get_x_export") (param (ref null $point)) (result i32)
    (struct.get $point $x (local.get 0)))

  (func (export "get_y_export") (param (ref null $point)) (result i32)
    (struct.get $point $y (local.get 0)))
)
