;; Exercises what crosses the boundary to and from a host function: float bit
;; patterns, negative i32 arguments, and multi-value results. Compiled backends
;; marshal these by hand, so each one is a place the value can be mangled.
(module
  (import "host" "retF32" (func $retF32 (result f32)))
  (import "host" "retF64" (func $retF64 (result f64)))
  (import "host" "takeI32" (func $takeI32 (param i32) (result i32)))
  (import "host" "retPair" (func $retPair (result i32 i32)))

  (func (export "callRetF32") (result f32)
    (call $retF32))

  (func (export "callRetF64") (result f64)
    (call $retF64))

  ;; passes -1 straight through to the host
  (func (export "callTakeI32") (result i32)
    (call $takeI32 (i32.const -1)))

  ;; the host returns (10, 20); summing proves both results arrived
  (func (export "callRetPairSum") (result i32)
    (call $retPair)
    (i32.add))
)
