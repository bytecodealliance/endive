;; Borrows its table from the host, so the instance must not release it on close.
(module
  (import "env" "table" (table 4 funcref))

  (func (export "noop"))
)
