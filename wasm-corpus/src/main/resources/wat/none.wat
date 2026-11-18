(module
  ;; `none` is the bottom of the `any` hierarchy, a subtype of every heap type below `any`.

  (type $s (struct (field structref)))
  (type $a (array i8))

  (global $g structref (ref.null none))

  (func (export "anyref") (result i32)
    (ref.is_null (block (result anyref) (ref.null none)))
  )

  (func (export "eqref") (result i32)
    (ref.is_null (block (result eqref) (ref.null none)))
  )

  (func (export "i31ref") (result i32)
    (ref.is_null (block (result i31ref) (ref.null none)))
  )

  (func (export "structref") (result i32)
    (ref.is_null (block (result structref) (ref.null none)))
  )

  (func (export "arrayref") (result i32)
    (ref.is_null (block (result arrayref) (ref.null none)))
  )

  (func (export "concrete-struct") (result i32)
    (ref.is_null (block (result (ref null $s)) (ref.null none)))
  )

  (func (export "concrete-array") (result i32)
    (ref.is_null (block (result (ref null $a)) (ref.null none)))
  )

  (func (export "struct-field") (result i32)
    (ref.is_null (struct.get $s 0 (struct.new $s (ref.null none))))
  )

  (func (export "from-global") (result i32)
    (ref.is_null (global.get $g))
  )
)
