(module
  ;; A function calls a callee that ends in a tail call, then tail calls itself.

  (type $ret_i32 (func (result i32)))
  (type $s (struct (field i32)))
  (type $f_super (sub (func (param i32 (ref $s)) (result i32))))
  (type $f_sub (sub $f_super (func (param i32 structref) (result i32))))
  (import "env" "h_import" (func $h_import (result i32)))
  (tag $e)
  (table funcref (elem $h $h_sub))
  (elem declare func $h $h_import)

  (func $h (result i32) (i32.const 42))

  (func $g (result i32) (return_call $h))
  (func $a (result i32)
    (drop (call $g))
    (return_call $h))
  (func (export "return-call") (result i32) (call $a))

  (func $g_indirect (result i32)
    (return_call_indirect (type $ret_i32) (i32.const 0)))
  (func $a_indirect (result i32)
    (drop (call $g_indirect))
    (return_call_indirect (type $ret_i32) (i32.const 0)))
  (func (export "return-call-indirect") (result i32) (call $a_indirect))

  ;; the callee's signature is a subtype of the call type
  (func $h_sub (type $f_sub) (param i32 structref) (result i32)
    (i32.add (local.get 0) (struct.get $s 0 (ref.cast (ref $s) (local.get 1)))))
  (func $g_subtype (result i32)
    (return_call_indirect (type $f_super)
      (i32.const 40) (struct.new $s (i32.const 2)) (i32.const 1)))
  (func $a_subtype (result i32)
    (drop (call $g_subtype))
    (return_call_indirect (type $f_super)
      (i32.const 40) (struct.new $s (i32.const 2)) (i32.const 1)))
  (func (export "return-call-indirect-subtype") (result i32) (call $a_subtype))

  (func $g_struct_arg (result i32)
    (return_call $h_sub (i32.const 40) (struct.new $s (i32.const 2))))
  (func $a_struct_arg (result i32)
    (drop (call $g_struct_arg))
    (return_call $h_sub (i32.const 40) (struct.new $s (i32.const 2))))
  (func (export "return-call-struct-arg") (result i32) (call $a_struct_arg))

  (func $g_ref (result i32) (return_call_ref $ret_i32 (ref.func $h)))
  (func $a_ref (result i32)
    (drop (call $g_ref))
    (return_call_ref $ret_i32 (ref.func $h)))
  (func (export "return-call-ref") (result i32) (call $a_ref))

  (func $g_import (result i32) (return_call $h_import))
  (func $a_import (result i32)
    (drop (call $g_import))
    (return_call $h_import))
  (func (export "return-call-import") (result i32) (call $a_import))

  (func $g_ref_import (result i32) (return_call_ref $ret_i32 (ref.func $h_import)))
  (func $a_ref_import (result i32)
    (drop (call $g_ref_import))
    (return_call_ref $ret_i32 (ref.func $h_import)))
  (func (export "return-call-ref-import") (result i32) (call $a_ref_import))

  (func $thrower (result i32) (throw $e))
  (func $g_throw (result i32) (return_call $thrower))
  (func $a_catch (result i32)
    (block $caught
      (try_table (catch $e $caught)
        (drop (call $g_throw))))
    (return_call $h))
  (func (export "return-call-after-catch") (result i32) (call $a_catch))
)
