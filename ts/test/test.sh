#!/usr/bin/env bash
# TypeScript golden-file test runner.
#
# Runs every .flox program in ../examples and ../test/cases through the
# TS interpreter and diffs the combined stdout+stderr against the existing
# golden files in ../test/expected/. Same .expected files used by the
# Java test runner, so passing here proves byte-identical behaviour.
#
# Usage:
#   bash ts/test/test.sh

set -u
cd "$(dirname "$0")/.."

PASS=0
FAIL=0
declare -a FAILED_NAMES=()

run_case() {
  local flox="$1"
  local name
  name=$(basename "$flox" .flox)
  local expected="../test/expected/$name.expected"

  if [[ ! -f "$expected" ]]; then
    printf '  SKIP    %s (no expected file)\n' "$name"
    return
  fi

  local actual_tmp
  actual_tmp=$(mktemp)
  npx --no-install tsx src/cli.ts "$flox" > "$actual_tmp" 2>&1

  if diff -q "$expected" "$actual_tmp" > /dev/null; then
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
for f in ../examples/*.flox; do
  [[ -f "$f" ]] || continue
  run_case "$f"
done

echo
echo "Cases:"
for f in ../test/cases/*.flox; do
  [[ -f "$f" ]] || continue
  run_case "$f"
done

echo
echo "$PASS passed, $FAIL failed."

if (( FAIL > 0 )); then
  echo
  echo "Diffs (expected vs actual, first 30 lines each):"
  for n in "${FAILED_NAMES[@]}"; do
    flox=$(find ../examples ../test/cases -name "$n.flox" | head -1)
    echo
    echo "--- $n ---"
    npx --no-install tsx src/cli.ts "$flox" 2>&1 | diff "../test/expected/$n.expected" - | head -30
  done
  exit 1
fi
