#!/usr/bin/env bash
# Тесты win:provision.

test_provision_passes_tools_dir_and_mise_version() {
  export FAKE_GUEST_SCENARIO=ok FAKE_GUEST_STEP=prepare
  run cli provision
  assert_eq "$RC" 0
  local log
  log=$(cat "$FAKE_VIRSH_LOG")
  assert_contains "$log" 'provision.ps1'
  assert_contains "$log" 'C:\\mimir-tools'
  assert_contains "$log" '-MiseVersion'
  assert_contains "$log" "$WINVM_MISE_VERSION"
  assert_contains "$OUT" "Подготовка завершена"
  assert_contains "$OUT" "mise run win:snapshot"
}

test_provision_without_flag_never_copies_secrets() {
  export FAKE_GUEST_SCENARIO=ok FAKE_GUEST_STEP=prepare FAKE_SECRET_SEEN="$TEST_TMP/secret_seen"
  run cli provision
  assert_eq "$RC" 0
  assert_no_file "$TEST_TMP/secret_seen"
  assert_no_file "$WINVM_HOST_DIR/tmp/mise.local.toml"
  assert_no_file "$WINVM_HOST_DIR/src/mise.local.toml"
  assert_not_contains "$(cat "$FAKE_VIRSH_LOG")" 'mise.local.toml'
}

test_with_secrets_moves_secret_then_removes_tmp_copy() {
  export FAKE_GUEST_SCENARIO=ok FAKE_GUEST_STEP=prepare FAKE_SECRET_SEEN="$TEST_TMP/secret_seen"
  run cli provision --with-secrets
  assert_eq "$RC" 0
  assert_contains "$(cat "$FAKE_VIRSH_LOG")" 'move'
  assert_contains "$(cat "$FAKE_VIRSH_LOG")" 'C:\\mimir\\mise.local.toml'
  assert_file "$TEST_TMP/secret_seen"
  assert_no_file "$WINVM_HOST_DIR/tmp/mise.local.toml"
}

test_with_secrets_removes_tmp_copy_even_on_failure() {
  export FAKE_GUEST_SCENARIO=ok FAKE_FAIL_ON=move FAKE_EXIT=1
  run cli provision --with-secrets
  [[ $RC -ne 0 ]] || fail "expected failure"
  assert_contains "$(cat "$FAKE_VIRSH_LOG")" 'move'
  assert_no_file "$WINVM_HOST_DIR/tmp/mise.local.toml"
}

test_provision_no_user_exits_4() {
  export FAKE_FAIL_ON=provision.ps1 FAKE_EXIT=4
  run cli provision
  assert_eq "$RC" 4
  assert_contains "$ERR" "No user is logged in to Windows — log in via virt-manager and retry"
}

test_with_secrets_falls_back_to_main_checkout_in_linked_worktree() {
  local repo wt
  repo=$(make_repo)
  git -C "$repo" -c user.email=t@t -c user.name=t commit -qm init
  wt="$TEST_TMP/wt"
  git -C "$repo" worktree add -q "$wt"
  assert_no_file "$wt/mise.local.toml"
  export FAKE_GUEST_SCENARIO=ok FAKE_GUEST_STEP=prepare FAKE_SECRET_SEEN="$TEST_TMP/secret_seen"
  CLI_CWD="$wt" run cli provision --with-secrets
  assert_eq "$RC" 0
  assert_file "$TEST_TMP/secret_seen"
  assert_no_file "$WINVM_HOST_DIR/tmp/mise.local.toml"
}
