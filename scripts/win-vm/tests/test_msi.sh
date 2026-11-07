#!/usr/bin/env bash
# Тесты win:msi и win:uninstall.

test_msi_request_upgrade_flag() {
  export FAKE_GUEST_SCENARIO=ok FAKE_GUEST_STEP=install
  local out="$WINVM_HOST_DIR/out"
  run cli msi --upgrade
  assert_eq "$RC" 0
  assert_eq "$(jq -r .action "$out/request.json")" msi
  assert_eq "$(jq -r .upgrade "$out/request.json")" true
  assert_contains "$OUT" "MSI установлен и запущен — смотрите в virt-manager"
  run cli msi
  assert_eq "$RC" 0
  assert_eq "$(jq -r .upgrade "$out/request.json")" false
}

test_msi_failed_build_tails_build_log() {
  export FAKE_GUEST_SCENARIO=failed FAKE_GUEST_STEP=build
  run cli msi
  assert_eq "$RC" 1
  assert_contains "$ERR" "build.log"
  assert_contains "$ERR" "line 50"
  assert_not_contains "$ERR" "line 10"$'\n'
}

test_uninstall_prints_report() {
  export FAKE_GUEST_SCENARIO=ok FAKE_GUEST_STEP=uninstall FAKE_REPORT="Start menu shortcuts: none"
  run cli uninstall
  assert_eq "$RC" 0
  assert_eq "$(jq -r .action "$WINVM_HOST_DIR/out/request.json")" uninstall
  assert_contains "$OUT" "Start menu shortcuts: none"
}

test_msi_unknown_flag_exits_64() {
  run cli msi --bogus
  assert_eq "$RC" 64
}

test_agent_uninstall_looks_in_hklm_too() {
  # MSI ставится в HKLM (найдено вживую): поиск только в HKCU «не находил» установленное
  local src
  src=$(<"$WINVM_TEST_ROOT/scripts/win-vm/guest/agent.ps1")
  assert_contains "$src" "'HKCU:', 'HKLM:'"
}
