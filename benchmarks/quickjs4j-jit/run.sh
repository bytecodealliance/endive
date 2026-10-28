#!/usr/bin/env bash
#
# Builds quickjs4j twice -- once against endive at this branch's merge-base with
# main, once against endive at this branch -- and measures QuickJS throughput
# with each. Writes a Markdown table and an SVG chart to results/.
#
# Nothing in quickjs4j is modified or patched. The only difference between the
# two runs is which build of the endive compiler precompiled the Javy plugin
# bytecode that ships inside the quickjs4j jar.
#
# Usage:
#   ./run.sh [path-to-quickjs4j-checkout]
#
# Environment overrides:
#   BASE_REV   endive revision for the "before" build (default: merge-base with main)
#   HEAD_REV   endive revision for the "after" build  (default: HEAD)
#   WINDOWS    timed windows per run       (default: 12)
#   ITERS      iterations per window       (default: 5)
#   JAVA_HOME  JDK to build and measure with
#
# Note: both endive builds install as the same Maven coordinates, so they are
# built sequentially and your local ~/.m2 is left holding the "after" build.

set -euo pipefail

cd "$(dirname "$0")"
BENCH_DIR="$PWD"
OUT_DIR="$BENCH_DIR/results"
WORK_DIR="$BENCH_DIR/.work"
ENDIVE_REPO="$(git rev-parse --show-toplevel)"

QUICKJS4J="${1:-}"
if [[ -z "$QUICKJS4J" ]]; then
  for candidate in "$ENDIVE_REPO/../quickjs4j-cloned" "$ENDIVE_REPO/../quickjs4j"; do
    [[ -d "$candidate" ]] && QUICKJS4J="$candidate" && break
  done
fi
if [[ -z "$QUICKJS4J" || ! -d "$QUICKJS4J" ]]; then
  QUICKJS4J="$WORK_DIR/quickjs4j"
  if [[ ! -d "$QUICKJS4J" ]]; then
    echo "==> cloning quickjs4j (no checkout supplied)"
    mkdir -p "$WORK_DIR"
    git clone --depth 50 https://github.com/roastedroot/quickjs4j.git "$QUICKJS4J"
  fi
fi
QUICKJS4J="$(cd "$QUICKJS4J" && pwd)"

# quickjs4j does not ship a Maven wrapper; endive does.
if [[ -x "$QUICKJS4J/mvnw" ]]; then
  QJ_MVN="$QUICKJS4J/mvnw"
elif command -v mvn >/dev/null 2>&1; then
  QJ_MVN="$(command -v mvn)"
else
  echo "need either $QUICKJS4J/mvnw or mvn on PATH to build quickjs4j" >&2
  exit 1
fi

WINDOWS="${WINDOWS:-12}"
ITERS="${ITERS:-5}"
BASE_REV="${BASE_REV:-$(git -C "$ENDIVE_REPO" merge-base HEAD main)}"
HEAD_REV="${HEAD_REV:-$(git -C "$ENDIVE_REPO" rev-parse HEAD)}"

ENDIVE_VERSION="$(cd "$ENDIVE_REPO" && ./mvnw -q -Dexec.executable=echo \
  -Dexec.args='${project.version}' --non-recursive exec:exec 2>/dev/null | tail -1)"

# Redline's Panama runner targets Java 25, so an older JDK cannot build it and
# has to fall back to a released endive that ships it prebuilt. From 25 up,
# build Redline from this very checkout instead, so all three configurations
# share one endive and the comparison is like-for-like.
JDK_FEATURE="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F'= *' '/java.specification.version/{print $2}' | cut -d. -f1)"
if [[ -z "${REDLINE_ENDIVE_VERSION:-}" ]]; then
  if [[ "${JDK_FEATURE:-0}" -ge 25 ]]; then
    REDLINE_ENDIVE_VERSION="$ENDIVE_VERSION"
  else
    REDLINE_ENDIVE_VERSION="1.1.0"
  fi
fi

mkdir -p "$OUT_DIR" "$WORK_DIR/jars"

echo "=============================================================="
echo " endive repo    : $ENDIVE_REPO"
echo " quickjs4j      : $QUICKJS4J   (unmodified)"
echo " before (base)  : $(git -C "$ENDIVE_REPO" log --oneline -1 "$BASE_REV")"
echo " after  (branch): $(git -C "$ENDIVE_REPO" log --oneline -1 "$HEAD_REV")"
echo " endive version : $ENDIVE_VERSION"
echo " windows x iters: $WINDOWS x $ITERS"
echo "=============================================================="

# Build endive at $1 into the local repository, from a detached worktree so the
# caller's checkout is never touched.
build_endive() {
  local rev="$1"
  local name="$2"
  local wt="$WORK_DIR/endive-$name"
  echo "==> [$name] building endive at ${rev:0:12}"
  rm -rf "$wt"
  git -C "$ENDIVE_REPO" worktree remove --force "$wt" >/dev/null 2>&1 || true
  git -C "$ENDIVE_REPO" worktree add -q --detach "$wt" "$rev"
  (cd "$wt" && ./mvnw -q install -DskipTests -Ddev \
      -pl compiler,compiler-maven-plugin,build-time-compiler -am) \
    > "$WORK_DIR/endive-$name-build.log" 2>&1 \
    || { echo "    endive build FAILED -- see $WORK_DIR/endive-$name-build.log"; exit 1; }
  git -C "$ENDIVE_REPO" worktree remove --force "$wt" >/dev/null 2>&1 || true
}

# Build endive's redline modules at $1 into the local repository. Kept separate
# from build_endive because Redline is measured at main, not at this branch: the
# branch does not touch the Redline path, and building it from main is both what
# review asked for and the cleaner attribution.
build_endive_redline() {
  local rev="$1"
  local wt="$WORK_DIR/endive-redline"
  echo "==> [redline] building endive's redline modules at ${rev:0:12}"
  rm -rf "$wt"
  git -C "$ENDIVE_REPO" worktree remove --force "$wt" >/dev/null 2>&1 || true
  git -C "$ENDIVE_REPO" worktree add -q --detach "$wt" "$rev"
  (cd "$wt" && ./mvnw -q install -DskipTests -Ddev \
      -pl redline/api,redline/compiler,redline/build-time-compiler,redline/runner-jffi,redline/runner -am) \
    > "$WORK_DIR/endive-redline-build.log" 2>&1 \
    || { echo "    endive redline build FAILED -- see $WORK_DIR/endive-redline-build.log"; }
  git -C "$ENDIVE_REPO" worktree remove --force "$wt" >/dev/null 2>&1 || true
}

# Rebuild quickjs4j core against whatever endive is currently installed, and
# keep the resulting jar.
build_quickjs4j() {
  local name="$1"
  echo "==> [$name] building quickjs4j core against endive $ENDIVE_VERSION"
  (cd "$QUICKJS4J" && "$QJ_MVN" -q install -pl core -am -DskipTests \
      -Dendive.version="$ENDIVE_VERSION" \
      -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dgpg.skip=true) \
    > "$WORK_DIR/quickjs4j-$name-build.log" 2>&1 \
    || { echo "    quickjs4j build FAILED -- see $WORK_DIR/quickjs4j-$name-build.log"; exit 1; }

  local jar
  jar="$(find "$QUICKJS4J/core/target" -maxdepth 1 -name 'quickjs4j-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' | head -1)"
  [[ -n "$jar" ]] || { echo "    no quickjs4j jar produced"; exit 1; }
  cp "$jar" "$WORK_DIR/jars/quickjs4j-$name.jar"

  # How the compiler treated the plugin, straight from the build log.
  local split interp
  split=$(grep -c 'chunks to stay JIT-eligible' "$WORK_DIR/quickjs4j-$name-build.log" || true)
  interp=$(grep -c 'using interpreted mode' "$WORK_DIR/quickjs4j-$name-build.log" || true)
  echo "    functions split: $split   routed to interpreter: $interp"
  echo "$split" > "$WORK_DIR/jars/$name.split"
  echo "$interp" > "$WORK_DIR/jars/$name.interp"
}

# quickjs4j's own runtime dependencies, minus its core jar (that is the variable).
classpath_without_core() {
  (cd "$QUICKJS4J/core" && "$QJ_MVN" -q dependency:build-classpath \
      -Dmdep.outputFile="$WORK_DIR/cp.txt" -Dendive.version="$ENDIVE_VERSION" >/dev/null 2>&1)
  cat "$WORK_DIR/cp.txt"
}

measure() {
  local name="$1" label="$2"
  local cp="$WORK_DIR/jars/quickjs4j-$name.jar:$(cat "$WORK_DIR/cp.txt"):$WORK_DIR/classes"
  echo "==> [$name] measuring"
  java -cp "$cp" JitBenchmark "$label" "$WINDOWS" "$ITERS" "$OUT_DIR/$name.csv" \
    | tee "$OUT_DIR/$name.log"
}

# ---- before ----
build_endive "$BASE_REV" before
build_quickjs4j before
DEPS="$(classpath_without_core)"
mkdir -p "$WORK_DIR/classes"
javac -cp "$WORK_DIR/jars/quickjs4j-before.jar:$DEPS" -d "$WORK_DIR/classes" src/JitBenchmark.java
measure before "before (unsplit)"

# ---- redline (optional third point of comparison) ----
# Endive's experimental Redline backend compiles the module to native code via
# Cranelift instead of to JVM bytecode, so it shows how much of the gap to
# native the bytecode path closes. Measured here, while main's endive is still
# the installed snapshot, so Redline comes from main rather than from this
# branch. Needs quickjs4j's own redline module, which arrived in
# roastedroot/quickjs4j#209 -- skipped when the checkout does not have it.
REDLINE_STATUS="not run"
if [[ -d "$QUICKJS4J/redline" ]]; then
  if [[ "$REDLINE_ENDIVE_VERSION" == "$ENDIVE_VERSION" ]]; then
    build_endive_redline "$BASE_REV"
  fi
  echo "==> [redline] building quickjs4j redline module against endive $REDLINE_ENDIVE_VERSION"
  if (cd "$QUICKJS4J" && "$QJ_MVN" -q install -pl redline -am -DskipTests \
        -Dendive.version="$REDLINE_ENDIVE_VERSION" \
        -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dgpg.skip=true) \
      > "$WORK_DIR/quickjs4j-redline-build.log" 2>&1; then
    RJAR="$(find "$QUICKJS4J/redline/target" -maxdepth 1 -name 'quickjs4j-redline-experimental-*.jar' \
          ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' | head -1)"
    CJAR="$(find "$QUICKJS4J/core/target" -maxdepth 1 -name 'quickjs4j-*.jar' \
          ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' | head -1)"
    (cd "$QUICKJS4J/redline" && "$QJ_MVN" -q dependency:build-classpath \
        -Dmdep.outputFile="$WORK_DIR/cp-redline.txt" \
        -Dendive.version="$REDLINE_ENDIVE_VERSION" >/dev/null 2>&1) || true
    if [[ -n "$RJAR" && -n "$CJAR" && -s "$WORK_DIR/cp-redline.txt" ]]; then
      cp "$RJAR" "$WORK_DIR/jars/quickjs4j-redline.jar"
      cp "$CJAR" "$WORK_DIR/jars/quickjs4j-redline-core.jar"
      echo "==> [redline] measuring"
      java -cp "$WORK_DIR/jars/quickjs4j-redline.jar:$WORK_DIR/jars/quickjs4j-redline-core.jar:$(cat "$WORK_DIR/cp-redline.txt"):$WORK_DIR/classes" \
        JitBenchmark "redline (native)" "$WINDOWS" "$ITERS" "$OUT_DIR/redline.csv" \
        | tee "$OUT_DIR/redline.log"
      # The provider returns empty when it finds no native code for the platform,
      # leaving the default backend in place; only count it when the run itself
      # reports a native backend.
      if grep -q "native," "$OUT_DIR/redline.log"; then
        REDLINE_STATUS="ok"
      else
        REDLINE_STATUS="fell back to the default backend -- not a native measurement"
        rm -f "$OUT_DIR/redline.csv"
      fi
    else
      REDLINE_STATUS="build produced no usable jar/classpath"
    fi
  else
    REDLINE_STATUS="build failed -- see $WORK_DIR/quickjs4j-redline-build.log"
  fi
else
  REDLINE_STATUS="skipped: no redline module in $QUICKJS4J (see roastedroot/quickjs4j#209)"
fi
echo "    redline: $REDLINE_STATUS"

# ---- after ----
build_endive "$HEAD_REV" after
build_quickjs4j after
measure after "after (split)"

# ---- report ----
echo "==> writing report"
REDLINE_ARGS=()
if [[ -f "$OUT_DIR/redline.csv" ]]; then
  REDLINE_ARGS=(--redline "$OUT_DIR/redline.csv"
                --redline-endive "$REDLINE_ENDIVE_VERSION"
                --redline-rev "$(git -C "$ENDIVE_REPO" log --oneline -1 "$BASE_REV")"
                --redline-runner "$(grep -m1 '^backend:' "$OUT_DIR/redline.log" | sed 's/^backend: *//')")
fi
python3 report.py \
  --before "$OUT_DIR/before.csv" \
  --after "$OUT_DIR/after.csv" \
  --before-split "$(cat "$WORK_DIR/jars/before.split")" \
  --after-split "$(cat "$WORK_DIR/jars/after.split")" \
  --before-interp "$(cat "$WORK_DIR/jars/before.interp")" \
  --after-interp "$(cat "$WORK_DIR/jars/after.interp")" \
  --base-rev "$(git -C "$ENDIVE_REPO" log --oneline -1 "$BASE_REV")" \
  --head-rev "$(git -C "$ENDIVE_REPO" log -1 --format=%s "$HEAD_REV")" \
  --jdk "$(java -version 2>&1 | head -1)" \
  "${REDLINE_ARGS[@]}" \
  --out-md "$OUT_DIR/results.md" \
  --out-svg "$OUT_DIR/results.svg"

echo
cat "$OUT_DIR/results.md"
echo
echo "chart: $OUT_DIR/results.svg"
