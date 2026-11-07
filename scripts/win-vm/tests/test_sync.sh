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

test_sync_keeps_nested_source_dir_named_build() {
  local repo src
  repo=$(make_repo)
  src="$WINVM_SHARE/mimir/src"
  echo "/build/" >"$repo/.gitignore"
  mkdir -p "$repo/pkg/build"
  echo kt >"$repo/pkg/build/Keep.kt"
  git -C "$repo" add .gitignore pkg/build/Keep.kt
  run sync_from "$repo"
  assert_eq "$RC" 0
  assert_file "$src/pkg/build/Keep.kt"
  assert_no_file "$src/build"
}

test_guest_sync_does_not_exclude_build_by_bare_name() {
  # /XD build по голому имени вырезает и исходники (build-logic/.../dev/mimir/build)
  local line
  line=$(grep -E '^& robocopy' "$WINVM_TEST_ROOT/scripts/win-vm/guest/sync.ps1")
  assert_not_contains "$line" " build "
  assert_contains "$line" "@buildDirs"
}

test_sync_skips_files_ignored_via_info_exclude() {
  # глобальный/info-exclude gitignore (секреты: .env, *.key) rsync-фильтр по .gitignore не видел
  local repo src
  repo=$(make_repo)
  src="$WINVM_SHARE/mimir/src"
  echo "*.key" >>"$repo/.git/info/exclude"
  echo k >"$repo/server.key"
  run sync_from "$repo"
  assert_eq "$RC" 0
  assert_file "$src/new.txt"
  assert_no_file "$src/server.key"
}

test_sync_reports_source_root_branch_and_commit() {
  local repo sha
  repo=$(make_repo)
  git -C "$repo" -c user.email=t@t -c user.name=t commit -qm init
  sha=$(git -C "$repo" rev-parse --short HEAD)
  run sync_from "$repo"
  assert_eq "$RC" 0
  assert_contains "$ERR" "Sync: $repo (branch main, commit $sha, uncommitted changes) ->"
}

test_sync_takes_tree_from_mise_original_cwd() {
  # mise выполняет задачу в каталоге своего mise.toml: из ворктри без задач win:* — в родительском checkout
  local repo other src
  repo=$(make_repo)
  other="$TEST_TMP/other"
  mkdir -p "$other/scripts/win-vm/guest"
  git -C "$other" init -q -b feature
  echo agent >"$other/scripts/win-vm/guest/agent.ps1"
  echo mine >"$other/only-in-other.txt"
  src="$WINVM_SHARE/mimir/src"
  MISE_TASK_NAME=win:run MISE_ORIGINAL_CWD="$other" run sync_from "$repo"
  assert_eq "$RC" 0
  assert_file "$src/only-in-other.txt"
  assert_no_file "$src/a.txt"
  assert_contains "$ERR" "Sync: $other (branch feature"
}

test_sync_refuses_tree_without_win_vm_scripts() {
  # robocopy /MIR стёр бы agent.ps1 в госте, и задача MimirDev перестала бы работать
  local repo
  repo=$(make_repo)
  rm -r "$repo/scripts"
  run sync_from "$repo"
  assert_eq "$RC" 1
  assert_contains "$ERR" "$repo has no scripts/win-vm"
  assert_no_file "$WINVM_SHARE/mimir/src/a.txt"
}
