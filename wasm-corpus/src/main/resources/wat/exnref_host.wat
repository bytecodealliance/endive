(module
  ;; An exnref handed to the host and handed straight back must survive the round trip.
  (import "host" "roundtrip" (func $roundtrip (param exnref) (result exnref)))

  (tag $e (param i32))
  (func $throw (param i32) (throw $e (local.get 0)))

  (func $capture (param $val i32) (result (ref exn))
    (block $h (result (ref exn))
      (try_table (catch_all_ref $h) (call $throw (local.get $val)))
      (unreachable)))

  (func (export "roundtrip-payload") (param $val i32) (result i32)
    (block $h (result i32 (ref exn))
      (try_table (result i32) (catch_ref $e $h)
        (call $roundtrip (call $capture (local.get $val)))
        (throw_ref))
      (return))
    (drop))
)
