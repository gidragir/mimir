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

# make_repo — временный git-репозиторий как рабочее дерево; печатает путь.
make_repo() {
  local repo="$TEST_TMP/repo"
  mkdir -p "$repo/build" "$repo/docs"
  git -C "$repo" init -q
  echo tracked >"$repo/a.txt"
  echo secret >"$repo/mise.local.toml"
  echo out >"$repo/build/x"
  echo "build/" >"$repo/.gitignore"
  git -C "$repo" add a.txt .gitignore
  echo untracked >"$repo/new.txt"
  printf '%s' "$repo"
}

sync_from() {
  (cd "$1" && winvm_sync)
}

# cli <args...> — winvm.sh из временного репозитория (нужен git-корень для sync).
cli() {
  local repo
  repo=$(make_repo)
  (cd "$repo" && bash "$WINVM_TEST_ROOT/scripts/win-vm/winvm.sh" "$@")
}
