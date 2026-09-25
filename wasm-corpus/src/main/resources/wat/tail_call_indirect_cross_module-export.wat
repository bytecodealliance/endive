(module
  (func $f (result i32) (i32.const 7))
  (table (export "table") funcref (elem $f)))
