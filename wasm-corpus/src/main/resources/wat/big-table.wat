;; A table large enough that failing to release it shows up as real memory.
;; Bounded so the whole thing is allocated and touched up front.
(module
  (table 100000 100000 funcref)

  (func (export "noop"))
)
