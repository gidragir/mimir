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

test_agent_invoke_logged_does_not_use_call_operator() {
  # & cmd.exe зависает, пока демон Gradle держит унаследованный канал (найдено на win:msi)
  local src
  src=$(<"$WINVM_TEST_ROOT/scripts/win-vm/guest/agent.ps1")
  assert_not_contains "$src" '& cmd.exe /c'
}

test_run_stops_previous_instance_and_says_so() {
  export FAKE_GUEST_SCENARIO=launched FAKE_PREV_APP=alive WINVM_NO_TAIL=1
  run cli run
  assert_eq "$RC" 0
  assert_contains "$ERR" "Stopped previous Mimir in VM: pid 4242 (java.exe)"
  assert_contains "$(cat "$FAKE_VIRSH_LOG")" 'stop.ps1'
}

test_run_says_when_no_previous_instance() {
  export FAKE_GUEST_SCENARIO=launched WINVM_NO_TAIL=1
  run cli run
  assert_eq "$RC" 0
  assert_contains "$ERR" "No previous Mimir instance in VM"
}

test_run_busy_agent_exits_5_before_stopping_app() {
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  echo '{"id":"old","action":"msi"}' >"$out/request.json"
  echo '{"id":"old","state":"running","step":"build","exitCode":0}' >"$out/status.json"
  export FAKE_AGENT_PROCESS=alive FAKE_PREV_APP=alive WINVM_NO_TAIL=1
  run cli run
  assert_eq "$RC" 5
  assert_not_contains "$(cat "$FAKE_VIRSH_LOG")" 'stop.ps1'
}

test_run_rotates_logs_after_stopping_previous_instance() {
  # Gradle прежнего экземпляра дописывает BUILD FAILED уже после остановки приложения (фейк stop.ps1)
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  echo "old output" >"$out/run.log"
  echo "old err" >"$out/run.err.log"
  export FAKE_GUEST_SCENARIO=launched FAKE_PREV_APP=alive WINVM_NO_TAIL=1
  run cli run
  assert_eq "$RC" 0
  assert_contains "$(cat "$out/run.prev.log")" "BUILD FAILED in 12h 54m"
  assert_contains "$(cat "$out/run.err.prev.log")" "old err"
  assert_eq "$(cat "$out/run.log")" "current run"
}

test_run_tail_shows_only_current_run_and_exit_code() {
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  echo "Execution failed for task ':apps:desktop:run'. BUILD FAILED in 12h 54m" >"$out/run.log"
  export FAKE_GUEST_SCENARIO=launched FAKE_GUEST_FINAL=failed FAKE_GUEST_EXIT=3
  run cli run
  assert_eq "$RC" 3
  assert_contains "$OUT" "current run"
  assert_not_contains "$OUT$ERR" "BUILD FAILED"
  assert_contains "$ERR" "Mimir in VM exited with code 3"
}

test_run_tail_reports_clean_exit() {
  export FAKE_GUEST_SCENARIO=launched FAKE_GUEST_FINAL=ok
  run cli run
  assert_eq "$RC" 0
  assert_contains "$ERR" "Mimir in VM exited with code 0"
}

test_run_tail_stops_when_superseded_by_newer_request() {
  export FAKE_GUEST_SCENARIO=launched FAKE_GUEST_FINAL=superseded
  run cli run
  assert_eq "$RC" 0
  assert_contains "$ERR" "superseded by a newer win:* request"
}

test_run_supersedes_previous_request_before_stopping_its_app() {
  # иначе прежний агент видит свой id и пишет failed (Gradle прибитого приложения — код 1),
  # а прежний win:run принимает это за собственное падение
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  echo '{"id":"old","action":"run"}' >"$out/request.json"
  echo '{"id":"old","state":"launched","step":"run","exitCode":0}' >"$out/status.json"
  export FAKE_GUEST_SCENARIO=launched FAKE_PREV_APP=alive WINVM_NO_TAIL=1 FAKE_STOP_SAW="$TEST_TMP/stop-saw"
  run cli run
  assert_eq "$RC" 0
  [[ $(cat "$FAKE_STOP_SAW") != old ]] || fail "previous request was not superseded before stop.ps1"
}

test_run_tail_shows_app_output_written_past_gradle_client() {
  # клиент Gradle на Windows отдаёт вывод JavaExec только после выхода приложения
  export FAKE_GUEST_SCENARIO=launched FAKE_GUEST_FINAL=ok
  run cli run
  assert_eq "$RC" 0
  assert_contains "$OUT" "app heartbeat"
}

test_run_rotates_app_log() {
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  echo "old app" >"$out/run.app.log"
  export FAKE_GUEST_SCENARIO=launched WINVM_NO_TAIL=1
  run cli run
  assert_eq "$RC" 0
  assert_contains "$(cat "$out/run.app.prev.log")" "old app"
  assert_eq "$(cat "$out/run.app.log")" "app heartbeat"
}

test_agent_sends_app_output_to_its_own_log() {
  local src
  src=$(<"$WINVM_TEST_ROOT/scripts/win-vm/guest/agent.ps1")
  assert_contains "$src" "MIMIR_DEV_APP_LOG"
  assert_contains "$src" "run.app.log"
}

test_logs_run_includes_app_log() {
  export WINVM_NO_TAIL=1
  run cli logs run
  assert_eq "$RC" 0
  assert_contains "$OUT" "run.app.log"
}
