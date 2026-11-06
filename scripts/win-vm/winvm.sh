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
winvm_cmd_provision() { winvm_ensure_running; winvm_die 70 "provision is not implemented yet"; }
winvm_cmd_sync() { winvm_ensure_running; winvm_die 70 "sync is not implemented yet"; }
winvm_cmd_run() { winvm_ensure_running; winvm_die 70 "run is not implemented yet"; }
winvm_cmd_msi() { winvm_ensure_running; winvm_die 70 "msi is not implemented yet"; }
winvm_cmd_uninstall() { winvm_ensure_running; winvm_die 70 "uninstall is not implemented yet"; }
winvm_cmd_logs() { winvm_die 70 "logs is not implemented yet"; }
winvm_cmd_snapshot() { winvm_die 70 "snapshot is not implemented yet"; }
winvm_cmd_reset() { winvm_die 70 "reset is not implemented yet"; }

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
