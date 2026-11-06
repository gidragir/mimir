#!/usr/bin/env bash
# Хелперы тестов win-vm: run (захват кода/stdout/stderr) и ассерты.

# shellcheck disable=SC2034 # RC, OUT, ERR читаются тестами
# run <cmd...> — выполняет команду в подоболочке, сохраняет RC, OUT, ERR.
run() {
  local errf="$TEST_TMP/stderr"
  RC=0
  OUT=$("$@" 2>"$errf") || RC=$?
  ERR=$(cat "$errf")
}

fail() {
  printf 'ASSERT: %s\n' "$*" >&2
  return 1
}

assert_eq() { [[ "$1" == "$2" ]] || fail "expected '$2', got '$1'"; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "'$2' not found in: $1"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] || fail "'$2' unexpectedly found in: $1"; }
assert_file() { [[ -e "$1" ]] || fail "file missing: $1"; }
assert_no_file() { [[ ! -e "$1" ]] || fail "file unexpectedly exists: $1"; }
