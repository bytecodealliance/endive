;; Uses an imported memory, then traps while starting, so building it fails.
(module
  (import "env" "memory" (memory 1))

  (func $start
    unreachable)

  (start $start)
)
