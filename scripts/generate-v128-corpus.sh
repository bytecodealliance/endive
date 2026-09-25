#!/usr/bin/env bash
set -euo pipefail

# Regenerates the committed v128 WAST corpus with Wasmtime 49.0.0.
# The corpus is committed; CI never runs this script.
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
ROOT_DIR=$(cd -- "$SCRIPT_DIR/.." &>/dev/null && pwd)
WASMTIME=${WASMTIME:-wasmtime}
JAVA=${JAVA:-java}

cd "$ROOT_DIR"
./mvnw -q -DskipTests package -pl test-gen-lib -am
"$JAVA" -cp test-gen-lib/target/classes \
  run.endive.testgen.V128CorpusGenerator \
  "$WASMTIME" \
  wasm/src/main/java/run/endive/wasm/types/OpCode.java \
  runtime-tests/src/test/resources/wast/v128_corpus.wast
