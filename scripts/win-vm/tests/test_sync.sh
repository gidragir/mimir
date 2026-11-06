#!/usr/bin/env bash
# Тесты winvm_sync: rsync дерева в virsh-шару и вызов robocopy в госте.

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

test_sync_skips_symlinks() {
  local repo src
  repo=$(make_repo)
  src="$WINVM_SHARE/mimir/src"
  ln -s a.txt "$repo/link.txt"
  mkdir -p "$repo/linkdir/sub"
  ln -s ../a.txt "$repo/linkdir/sub/rel"
  mkdir -p "$src"
  ln -s stale-target "$src/stale-link"
  ln -s stale-target "$src/link.txt"
  run sync_from "$repo"
  assert_eq "$RC" 0
  assert_file "$src/a.txt"
  [[ ! -L $src/link.txt ]] || fail "symlink was copied to the share"
  [[ ! -L $src/linkdir/sub/rel ]] || fail "nested symlink was copied to the share"
  [[ ! -L $src/stale-link ]] || fail "stale symlink from an earlier sync was left in the share"
}
