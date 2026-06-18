(module
  (type $ft (func))
  (type $arr (array (mut funcref)))
  
  (func $dummy (type $ft))
  
  (func (export "copy_funcref_array") (result i32)
    ;; Create src array with 2 funcrefs
    (local $src (ref $arr))
    (local $dst (ref $arr))
    (local.set $src (array.new $arr (ref.func $dummy) (i32.const 2)))
    (local.set $dst (array.new_default $arr (i32.const 2)))
    ;; Copy src[0..2] -> dst[0..2]
    (array.copy $arr $arr (local.get $dst) (i32.const 0) (local.get $src) (i32.const 0) (i32.const 2))
    ;; Check dst[0] is not null (funcref was copied)
    (local.get $dst)
    (i32.const 0)
    (array.get $arr)
    (ref.is_null)
    ;; Should be 0 (not null)
  )
)
