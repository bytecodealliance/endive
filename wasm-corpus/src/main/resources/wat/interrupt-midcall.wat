;; The host function is called after the entry interrupt check has already
;; passed, and nothing loops afterwards, so a flag raised from inside it is
;; still set when the call returns normally. That is what the watchdog thread
;; does when it observes an interrupt near the end of a call.
(module
  (import "host" "raiseFlag" (func $raiseFlag))

  (func (export "callHost")
    (call $raiseFlag))

  (func (export "answer") (result i32)
    (i32.const 42))
)
