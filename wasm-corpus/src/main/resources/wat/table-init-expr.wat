;; A table declared with a non-null initialiser: every slot starts out holding
;; $f rather than null. Nothing writes to the table afterwards, so a backend
;; that null-fills instead comes up empty and traps on call_indirect.
(module
  (type $ft (func (result i32)))

  (func $f (type $ft)
    (i32.const 42))

  (table $t 2 2 funcref (ref.func $f))

  ;; index 1 is only reachable through the initialiser
  (func (export "callInitialised") (result i32)
    (call_indirect (type $ft) (i32.const 1)))
)
