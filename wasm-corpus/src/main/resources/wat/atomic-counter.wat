;; Counters bumped with atomics, to race against host atomics on the same addresses.
(module
  (import "env" "memory" (memory 1 1 shared))

  (func (export "add32") (param $n i32)
    (loop $l
      (drop (i32.atomic.rmw.add (i32.const 0) (i32.const 1)))
      (br_if $l (local.tee $n (i32.sub (local.get $n) (i32.const 1))))))

  ;; upper half of the int at 8
  (func (export "add16") (param $n i32)
    (loop $l
      (drop (i32.atomic.rmw16.add_u (i32.const 10) (i32.const 1)))
      (br_if $l (local.tee $n (i32.sub (local.get $n) (i32.const 1))))))

  ;; second byte of the int at 12
  (func (export "add8") (param $n i32)
    (loop $l
      (drop (i32.atomic.rmw8.add_u (i32.const 13) (i32.const 1)))
      (br_if $l (local.tee $n (i32.sub (local.get $n) (i32.const 1))))))

  (func (export "add64") (param $n i32)
    (loop $l
      (drop (i64.atomic.rmw.add (i32.const 16) (i64.const 1)))
      (br_if $l (local.tee $n (i32.sub (local.get $n) (i32.const 1)))))))
