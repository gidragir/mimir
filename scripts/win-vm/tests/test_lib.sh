#!/usr/bin/env bash
# Тесты lib.sh: проверка VM, агент, guest-exec.

test_missing_vm_exits_2() {
  export FAKE_VM_STATE=missing
  run winvm_ensure_running
  assert_eq "$RC" 2
  assert_contains "$ERR" "VM win11 not found in qemu:///system"
}

test_shut_off_vm_is_started() {
  export FAKE_VM_STATE="shut off"
  run winvm_ensure_running
  assert_eq "$RC" 0
  assert_contains "$(cat "$FAKE_VIRSH_LOG")" "start win11"
}

test_dead_agent_exits_3() {
  export FAKE_AGENT=down
  run winvm_ensure_running
  assert_eq "$RC" 3
  assert_contains "$ERR" "Guest agent in win11 is not responding"
}

test_exec_returns_guest_exit_code_and_output() {
  export FAKE_EXIT=7 FAKE_OUT=hello
  run winvm_exec cmd.exe /c x
  assert_eq "$RC" 7
  assert_eq "$OUT" hello
}
