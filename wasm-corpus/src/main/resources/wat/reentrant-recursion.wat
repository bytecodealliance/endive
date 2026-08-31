;; Recurses back into itself through the host rather than through a wasm call,
;; so every level re-enters the machine from the top. The stack guard has to
;; keep measuring against where the first call started, not where the latest
;; one did, or the budget grows by a frame on every level.
(module
  (import "host" "reenter" (func $reenter))

  (func (export "recurse")
    (call $reenter))
)
