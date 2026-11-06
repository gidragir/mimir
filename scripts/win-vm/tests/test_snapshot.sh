#!/usr/bin/env bash
# Тесты win:logs, win:snapshot, win:reset.

test_reset_without_yes_touches_nothing() {
  export FAKE_SNAPSHOTS=provisioned
  run cli reset
  assert_eq "$RC" 1
  assert_contains "$ERR" "This discards everything in win11 since snapshot provisioned. Re-run with --yes"
  local log
  log=$(cat "$FAKE_VIRSH_LOG")
  for forbidden in shutdown destroy snapshot-revert "start " sudo; do
    assert_not_contains "$log" "$forbidden"
  done
}

test_reset_with_yes_reverts_and_starts() {
  export FAKE_SNAPSHOTS=provisioned
  run cli reset --yes
  assert_eq "$RC" 0
  local order
  order=$(grep -nE 'shutdown win11|snapshot-revert win11 provisioned|sudo cp .*provisioned|start win11' "$FAKE_VIRSH_LOG" | sed 's/^[0-9]*://' | tr '\n' '|')
  assert_contains "$order" "shutdown win11|snapshot-revert win11 provisioned|sudo cp"
  assert_contains "$order" "start win11"
  local sd rv st
  sd=$(grep -n 'shutdown win11' "$FAKE_VIRSH_LOG" | head -1 | cut -d: -f1)
  rv=$(grep -n 'snapshot-revert win11 provisioned' "$FAKE_VIRSH_LOG" | head -1 | cut -d: -f1)
  st=$(grep -n '^start win11' "$FAKE_VIRSH_LOG" | tail -1 | cut -d: -f1)
  ((sd < rv && rv < st)) || fail "wrong order: shutdown=$sd revert=$rv start=$st"
}

test_reset_without_snapshot_fails() {
  export FAKE_SNAPSHOTS=""
  run cli reset --yes
  [[ $RC -ne 0 ]] || fail "expected failure"
  assert_contains "$ERR" "Snapshot provisioned not found"
  assert_not_contains "$(cat "$FAKE_VIRSH_LOG")" "shutdown"
}

test_snapshot_exists_exits_6() {
  export FAKE_SNAPSHOTS=provisioned
  run cli snapshot
  assert_eq "$RC" 6
  assert_contains "$ERR" "Snapshot provisioned already exists — delete it with virsh snapshot-delete first"
  assert_not_contains "$(cat "$FAKE_VIRSH_LOG")" "shutdown"
}

test_snapshot_shuts_down_snapshots_and_saves_nvram() {
  export FAKE_SNAPSHOTS=""
  run cli snapshot
  assert_eq "$RC" 0
  local log
  log=$(cat "$FAKE_VIRSH_LOG")
  assert_contains "$log" "shutdown win11"
  assert_contains "$log" "snapshot-create-as win11 provisioned --disk-only --atomic"
  assert_contains "$log" "sudo cp /var/lib/libvirt/qemu/nvram/win11_VARS.fd /var/lib/libvirt/qemu/nvram/win11_VARS.fd.provisioned"
}

test_snapshot_survives_sudo_refusal() {
  export FAKE_SNAPSHOTS="" FAKE_SUDO_FAIL=1
  run cli snapshot
  assert_eq "$RC" 0
  assert_contains "$ERR" "NVRAM"
}

test_logs_unknown_target_exits_64() {
  run cli logs bogus
  assert_eq "$RC" 64
}

test_logs_default_is_run_and_err_log() {
  export WINVM_NO_TAIL=1
  run cli logs
  assert_eq "$RC" 0
  assert_contains "$OUT" "run.log"
  assert_contains "$OUT" "run.err.log"
  run cli logs msi
  assert_contains "$OUT" "msi.log"
}
