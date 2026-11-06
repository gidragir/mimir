#!/usr/bin/env bash
# Тесты winvm_sync: rsync дерева в virsh-шару и вызов robocopy в госте.

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

test_sync_copies_tracked_and_untracked_skips_secrets_and_build() {
  local repo src
  repo=$(make_repo)
  src="$WINVM_SHARE/mimir/src"
  run sync_from "$repo"
  assert_eq "$RC" 0
  assert_file "$src/a.txt"
  assert_file "$src/new.txt"
  assert_no_file "$src/mise.local.toml"
  assert_no_file "$src/build"
  assert_no_file "$src/.git"
}

test_sync_deletes_removed_files_only_inside_src() {
  local repo src
  repo=$(make_repo)
  src="$WINVM_SHARE/mimir/src"
  run sync_from "$repo"
  echo neighbour >"$WINVM_SHARE/neighbour.rar"
  echo stale >"$src/stale.txt"
  rm "$repo/new.txt"
  run sync_from "$repo"
  assert_eq "$RC" 0
  assert_no_file "$src/new.txt"
  assert_no_file "$src/stale.txt"
  assert_file "$WINVM_SHARE/neighbour.rar"
}

test_sync_handles_spaces_and_cyrillic() {
  local repo
  repo=$(make_repo)
  echo note >"$repo/docs/Заметка 1.md"
  run sync_from "$repo"
  assert_eq "$RC" 0
  assert_file "$WINVM_SHARE/mimir/src/docs/Заметка 1.md"
}

test_sync_runs_guest_robocopy_from_share() {
  local repo
  repo=$(make_repo)
  run sync_from "$repo"
  assert_contains "$(cat "$FAKE_VIRSH_LOG")" 'Z:\\mimir\\src\\scripts\\win-vm\\guest\\sync.ps1'
}

test_sync_fails_on_guest_error() {
  local repo
  repo=$(make_repo)
  export FAKE_EXIT=8
  run sync_from "$repo"
  assert_eq "$RC" 8
  assert_contains "$ERR" "robocopy"
}
