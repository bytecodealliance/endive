(module
  (type $Point (struct (field $x i32) (field $y i32)))

  ;; (result i32 (ref $Point)) -- numeric then ref
  (func (export "int_then_ref") (param i32) (result i32 (ref $Point))
    local.get 0
    local.get 0
    local.get 0
    struct.new $Point
  )

  ;; (result (ref $Point) i32) -- ref then numeric
  (func (export "ref_then_int") (param i32) (result (ref $Point) i32)
    local.get 0
    local.get 0
    struct.new $Point
    local.get 0
  )

  ;; (result (ref $Point) (ref $Point)) -- two refs
  (func (export "two_refs") (param i32) (result (ref $Point) (ref $Point))
    local.get 0
    local.get 0
    struct.new $Point
    local.get 0
    local.get 0
    struct.new $Point
  )

  ;; (result i32 i32 (ref $Point)) -- two numerics + ref
  (func (export "two_ints_ref") (param i32) (result i32 i32 (ref $Point))
    local.get 0
    local.get 0
    local.get 0
    local.get 0
    struct.new $Point
  )

  ;; Helper: get x from Point
  (func (export "get_x") (param (ref $Point)) (result i32)
    local.get 0
    struct.get $Point $x
  )
)
