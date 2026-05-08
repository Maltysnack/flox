#!/usr/bin/env bash
# Golden-file test runner.
#
# Compares each .flox program's combined stdout+stderr against a
# captured .expected file in test/expected/.
#
# Usage:
#   bash test/test.sh            run all tests, fail if any drift
#   bash test/test.sh --update   regenerate every .expected from current output
#   bash test/test.sh case_name  run a single case by basename
#
# Sources:
#   examples/*.flox        full integration tests, must always pass
#   test/cases/*.flox      targeted edge cases (errors, forward refs, etc.)

set -u
cd "$(dirname "$0")/.."

# Build if not already built.
if [[ ! -d build/flox ]] || [[ ! -f build/flox/Flox.class ]]; then
  ./build.sh > /dev/null
fi

UPDATE=0
ONLY=""
if [[ "${1:-}" == "--update" ]]; then
  UPDATE=1
elif [[ -n "${1:-}" ]]; then
  ONLY="$1"
fi

mkdir -p test/expected

PASS=0
FAIL=0
SKIP=0
UPDATED=0
declare -a FAILED_NAMES=()

run_case() {
  local flox="$1"
  local name
  name=$(basename "$flox" .flox)
  if [[ -n "$ONLY" && "$name" != "$ONLY" ]]; then return; fi

  local expected="test/expected/$name.expected"
  local actual_tmp
  actual_tmp=$(mktemp)
  java -cp build flox.Flox "$flox" > "$actual_tmp" 2>&1

  if [[ "$UPDATE" == "1" ]]; then
    cp "$actual_tmp" "$expected"
    printf '  UPDATE  %s\n' "$name"
    UPDATED=$((UPDATED + 1))
  elif [[ ! -f "$expected" ]]; then
    printf '  SKIP    %s (no expected; run with --update to create)\n' "$name"
    SKIP=$((SKIP + 1))
  elif diff -q "$expected" "$actual_tmp" > /dev/null; then
    printf '  PASS    %s\n' "$name"
    PASS=$((PASS + 1))
  else
    printf '  FAIL    %s\n' "$name"
    FAILED_NAMES+=("$name")
    FAIL=$((FAIL + 1))
  fi

  rm -f "$actual_tmp"
}

echo
echo "Examples:"
for f in examples/*.flox; do
  [[ -f "$f" ]] || continue
  run_case "$f"
done

echo
echo "Cases:"
for f in test/cases/*.flox; do
  [[ -f "$f" ]] || continue
  run_case "$f"
done

echo
if [[ "$UPDATE" == "1" ]]; then
  echo "$UPDATED expected files written."
  exit 0
fi

echo "$PASS passed, $FAIL failed, $SKIP skipped."

if (( FAIL > 0 )); then
  echo
  echo "Diffs (expected vs actual, first 30 lines each):"
  for n in "${FAILED_NAMES[@]}"; do
    flox=$(find examples test/cases -name "$n.flox" | head -1)
    echo
    echo "--- $n ---"
    java -cp build flox.Flox "$flox" 2>&1 | diff "test/expected/$n.expected" - | head -30
  done
  exit 1
fi
