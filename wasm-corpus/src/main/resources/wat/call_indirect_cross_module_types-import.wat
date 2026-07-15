(module
  (import "test" "shared-table" (table 10 funcref))

  (func $provider (result i32)
    i32.const 42)

  (elem (i32.const 0) func $provider)
)
