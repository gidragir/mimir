#!/usr/bin/env bash
# Хостовый CLI тестового флоу на Windows-VM (см. docs/superpowers/specs/2026-11-07-windows-vm-test-flow-design.md).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
source "$here/lib.sh"

usage() {
  cat >&2 <<'USAGE'
usage: winvm.sh <command> [args]
  provision [--with-secrets]   разовая подготовка гостя (mise, JDK, задача MimirDev)
  sync                         синхронизировать дерево в VM
  run                          собрать и запустить desktop в сессии пользователя
  msi [--upgrade]              собрать и установить MSI
  uninstall                    удалить MSI и показать отчёт
  logs [build|run|msi]         хвост лога
  snapshot                     снапшот provisioned
  reset [--yes]                откат к снапшоту provisioned
USAGE
}

# Заглушки; последующие задачи заменяют их реализациями.
winvm_cmd_provision() {
  local with_secrets=0 id rc=0 secret_src tmp_secret
  while (($# > 0)); do
    case "$1" in
      --with-secrets) with_secrets=1 ;;
      *) usage; exit 64 ;;
    esac
    shift
  done
  winvm_ensure_running
  winvm_sync
  echo "Provision: installing mise $WINVM_MISE_VERSION and registering task MimirDev" >&2
  winvm_ps provision.ps1 -ToolsDir 'C:\mimir-tools' -MiseVersion "$WINVM_MISE_VERSION" || rc=$?
  if ((rc == 4)); then
    winvm_die 4 "No user is logged in to Windows — log in via virt-manager and retry"
  fi
  ((rc == 0)) || winvm_die "$rc" "provision.ps1 failed (exit $rc)"
  if ((with_secrets)); then
    secret_src=$(winvm_secret_source)
    tmp_secret="$WINVM_HOST_DIR/tmp/mise.local.toml"
    # секрет живёт в шаре только на время move; удаляется при любом выходе
    # shellcheck disable=SC2064 # путь подставляется сразу: к моменту EXIT локальная переменная уже не видна
    trap "rm -f $(printf '%q' "$tmp_secret")" EXIT
    install -m600 "$secret_src" "$tmp_secret"
    echo "Provision: copying mise.local.toml into C:\\mimir" >&2
    rc=0
    winvm_exec cmd.exe /c move /Y "$WINVM_SHARE_DRIVE\\mimir\\tmp\\mise.local.toml" 'C:\mimir\mise.local.toml' >/dev/null || rc=$?
    rm -f "$tmp_secret"
    ((rc == 0)) || winvm_die "$rc" "could not copy mise.local.toml into the guest (exit $rc)"
  fi
  id=$(winvm_request prepare)
  winvm_wait_status "$id" ok 3600
  echo "Подготовка завершена. Создайте снапшот: mise run win:snapshot"
}
winvm_cmd_sync() {
  winvm_ensure_running
  winvm_sync
  echo "Sync complete" >&2
}
winvm_cmd_run() {
  local id state
  winvm_ensure_running
  winvm_sync
  winvm_take_over
  winvm_rotate_logs run run.err run.app
  id=$(winvm_request run)
  winvm_wait_status "$id" launched,ok 1800
  state=$(jq -r '.state' "$WINVM_HOST_DIR/out/status.json")
  if [[ $state == ok ]]; then
    echo "Mimir в VM запустился и завершился с кодом 0"
    return 0
  fi
  echo "Mimir запущен в VM — смотрите в virt-manager"
  [[ -n ${WINVM_NO_TAIL:-} ]] || winvm_follow_run "$id"
}
winvm_cmd_msi() {
  local upgrade=false id
  while (($# > 0)); do
    case "$1" in
      --upgrade) upgrade=true ;;
      *) usage; exit 64 ;;
    esac
    shift
  done
  winvm_ensure_running
  winvm_sync
  # живой dev-экземпляр перехватил бы запуск установленного Mimir через single-instance IPC
  winvm_take_over
  id=$(winvm_request msi "$upgrade")
  winvm_wait_status "$id" ok 3600
  echo "MSI установлен и запущен — смотрите в virt-manager"
}
winvm_cmd_uninstall() {
  local id
  winvm_ensure_running
  winvm_sync
  id=$(winvm_request uninstall)
  winvm_wait_status "$id" ok 600
  cat "$WINVM_HOST_DIR/out/report.log"
}
winvm_cmd_logs() {
  local out="$WINVM_HOST_DIR/out" files
  case "${1:-run}" in
    run) files=("$out/run.log" "$out/run.err.log" "$out/run.app.log") ;;
    build) files=("$out/build.log") ;;
    msi) files=("$out/msi.log") ;;
    *) usage; exit 64 ;;
  esac
  mkdir -p "$out"
  if [[ -n ${WINVM_NO_TAIL:-} ]]; then
    printf '%s\n' "${files[@]}"
    return 0
  fi
  exec tail -F "${files[@]}"
}
winvm_cmd_snapshot() {
  local nvram
  winvm_vm_state >/dev/null
  winvm_snapshot_exists \
    && winvm_die 6 "Snapshot provisioned already exists — delete it with virsh snapshot-delete first"
  winvm_shutdown_wait
  _virsh snapshot-create-as "$WINVM_NAME" provisioned --disk-only --atomic >/dev/null
  nvram=$(winvm_nvram_path)
  [[ -z $nvram ]] || winvm_nvram_copy "$nvram" "$nvram.provisioned"
  echo "Snapshot provisioned created; VM $WINVM_NAME is shut off (the next win:* command starts it)" >&2
}
winvm_cmd_reset() {
  local yes=0 nvram
  while (($# > 0)); do
    case "$1" in
      --yes) yes=1 ;;
      *) usage; exit 64 ;;
    esac
    shift
  done
  winvm_vm_state >/dev/null
  winvm_snapshot_exists || winvm_die 1 "Snapshot provisioned not found — run win:snapshot first"
  if ((!yes)); then
    echo "This discards everything in $WINVM_NAME since snapshot provisioned. Re-run with --yes" >&2
    exit 1
  fi
  winvm_shutdown_wait
  _virsh snapshot-revert "$WINVM_NAME" provisioned >/dev/null
  nvram=$(winvm_nvram_path)
  if [[ -n $nvram ]]; then winvm_nvram_copy "$nvram.provisioned" "$nvram"; fi
  winvm_ensure_running
  echo "VM $WINVM_NAME reverted to snapshot provisioned" >&2
}

main() {
  local cmd=${1:-}
  [[ $# -gt 0 ]] && shift
  case "$cmd" in
    provision | sync | run | msi | uninstall | logs | snapshot | reset)
      command -v virsh >/dev/null || winvm_die 1 "virsh not found — install libvirt"
      command -v jq >/dev/null || winvm_die 1 "jq not found — run mise install"
      winvm_config
      "winvm_cmd_$cmd" "$@"
      ;;
    *)
      usage
      exit 64
      ;;
  esac
}

main "$@"
