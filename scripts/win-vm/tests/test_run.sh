#!/usr/bin/env bash
# Тесты протокола request/status и подкоманды run.

test_request_contains_action_id_and_app_name() {
  local out="$WINVM_HOST_DIR/out" id
  run winvm_request run
  assert_eq "$RC" 0
  id=$(jq -r .id "$out/request.json")
  [[ $id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "id is not uuid: $id"
  assert_eq "$(jq -r .action "$out/request.json")" run
  assert_eq "$(jq -r .appName "$out/request.json")" Mimir
  assert_eq "$(jq -r .upgrade "$out/request.json")" false
  assert_eq "$OUT" "$id"
  assert_contains "$(cat "$FAKE_VIRSH_LOG")" 'schtasks.exe'
}

test_run_reports_launched() {
  export FAKE_GUEST_SCENARIO=launched WINVM_NO_TAIL=1
  run cli run
  assert_eq "$RC" 0
  assert_contains "$OUT" "смотрите в virt-manager"
}

test_no_status_means_no_user_session_exit_4() {
  export FAKE_GUEST_SCENARIO=silent WINVM_NO_TAIL=1 WINVM_STATUS_TIMEOUT=2
  run cli run
  assert_eq "$RC" 4
  assert_contains "$ERR" "No user is logged in to Windows — log in via virt-manager and retry"
}

test_failed_build_prints_log_tail_and_code() {
  export FAKE_GUEST_SCENARIO=failed WINVM_NO_TAIL=1
  run cli run
  assert_eq "$RC" 1
  assert_contains "$ERR" "line 50"
  assert_contains "$ERR" "line 11"
  assert_not_contains "$ERR" "line 10"$'\n'
}

test_busy_agent_exits_5() {
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  echo '{"id":"old","action":"run"}' >"$out/request.json"
  echo '{"id":"old","state":"running","step":"build","exitCode":0}' >"$out/status.json"
  export FAKE_AGENT_PROCESS=alive
  run winvm_request run
  assert_eq "$RC" 5
  assert_contains "$ERR" "Another win:* request is running (run) — wait or run win:logs"
}

test_stale_running_status_is_overwritten() {
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  echo '{"id":"old","action":"run"}' >"$out/request.json"
  echo '{"id":"old","state":"running","step":"build","exitCode":0}' >"$out/status.json"
  export FAKE_AGENT_PROCESS=none
  run winvm_request run
  assert_eq "$RC" 0
  [[ $(jq -r .id "$out/request.json") != old ]] || fail "request was not overwritten"
  assert_no_file "$out/status.json"
}

test_agent_detects_launch_by_app_process_not_gradle_header() {
  # Gradle не печатает заголовок JavaExec-задачи, пока приложение молчит и работает
  local src
  src=$(<"$WINVM_TEST_ROOT/scripts/win-vm/guest/agent.ps1")
  assert_not_contains "$src" "> Task :apps:desktop:run"
  assert_contains "$src" "dev.mimir.desktop"
}
