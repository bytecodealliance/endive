;; Two instances share this memory: one grows it while the other is already running.
(module
  (import "env" "memory" (memory 1 2 shared))

  ;; Spins until the flag is set, then reads an address in the page grown meanwhile.
  (func (export "waitThenRead") (param $flag i32) (param $addr i32) (result i32)
    (block $done
      (loop $spin
        (br_if $done (i32.atomic.load (local.get $flag)))
        (br $spin)))
    (i32.load (local.get $addr)))

  ;; Spins until the flag is set, then returns the memory size.
  (func (export "waitThenSize") (param $flag i32) (result i32)
    (block $done
      (loop $spin
        (br_if $done (i32.atomic.load (local.get $flag)))
        (br $spin)))
    (memory.size))

  ;; Grows by one page, writes the value in it, then sets the flag.
  (func (export "growAndPublish") (param $flag i32) (param $addr i32) (param $value i32)
    (drop (memory.grow (i32.const 1)))
    (i32.store (local.get $addr) (local.get $value))
    (i32.atomic.store (local.get $flag) (i32.const 1))))
