;; "run" re-enters through the host, then loops so the interrupt flag is polled.
(module
  (import "host" "reenter" (func $reenter))
  (import "host" "raiseFlag" (func $raiseFlag))
  (import "host" "tick" (func $tick))

  (func (export "run") (result i32)
    (local $i i32)
    (call $reenter)
    (loop $l
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br_if $l (i32.lt_u (local.get $i) (i32.const 1000))))
    (local.get $i))

  (func (export "raise")
    (call $raiseFlag))

  (func (export "spin")
    (loop $l
      (call $tick)
      (br $l)))
)
