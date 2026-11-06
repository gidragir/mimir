#!/usr/bin/env bash
# Общие функции хостового CLI win-vm. Подключается через source.
# shellcheck shell=bash

_WINVM_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

winvm_die() {
  local code=$1
  shift
  printf 'winvm: %s\n' "$*" >&2
  exit "$code"
}

winvm_config() {
  : "${WINVM_NAME:=win11}"
  : "${WINVM_URI:=qemu:///system}"
  : "${WINVM_SHARE:=/srv/Shared}"
  : "${WINVM_SHARE_DRIVE:=Z:}"
  : "${WINVM_AGENT_TIMEOUT:=120}"
  : "${WINVM_POLL:=2}"
  : "${WINVM_EXEC_POLL:=1}"
  : "${WINVM_EXEC_TIMEOUT:=600}"
  : "${WINVM_STATUS_TIMEOUT:=30}"
  : "${WINVM_STATUS_POLL:=1}"
  WINVM_HOST_DIR="$WINVM_SHARE/mimir"
  WINVM_GUEST_DIR="$WINVM_SHARE_DRIVE\\mimir"
  export WINVM_NAME WINVM_URI WINVM_SHARE WINVM_SHARE_DRIVE WINVM_AGENT_TIMEOUT \
    WINVM_POLL WINVM_EXEC_POLL WINVM_EXEC_TIMEOUT WINVM_STATUS_TIMEOUT WINVM_STATUS_POLL WINVM_HOST_DIR WINVM_GUEST_DIR
}

_virsh() { virsh -c "$WINVM_URI" "$@"; }

_agent() { _virsh qemu-agent-command "$WINVM_NAME" "$1"; }

# winvm_ensure_running — VM существует, запущена и гостевой агент отвечает.
winvm_ensure_running() {
  local info state started
  info=$(_virsh dominfo "$WINVM_NAME" 2>/dev/null) \
    || winvm_die 2 "VM $WINVM_NAME not found in $WINVM_URI"
  state=$(awk -F: '/^State:/ { sub(/^[ \t]+/, "", $2); print $2 }' <<<"$info")
  if [[ $state != running ]]; then
    echo "Starting VM $WINVM_NAME..." >&2
    _virsh start "$WINVM_NAME" >/dev/null
  fi
  started=$SECONDS
  until _agent '{"execute":"guest-ping"}' >/dev/null 2>&1; do
    if ((SECONDS - started >= WINVM_AGENT_TIMEOUT)); then
      winvm_die 3 "Guest agent in $WINVM_NAME is not responding — check qemu-guest-agent service"
    fi
    sleep "$WINVM_POLL"
  done
}

# winvm_exec <exe> [args...] — guest-exec от SYSTEM; stdout/stderr гостя → наши, код возврата — гостя.
winvm_exec() {
  local exe=$1 req pid status started argv='[]'
  shift
  # jq --args путает аргументы вида -NoProfile с собственными опциями, поэтому массив собираем через stdin
  if (($# > 0)); then argv=$(printf '%s\0' "$@" | jq -Rsc 'split("\u0000")[:-1]'); fi
  req=$(jq -cn --arg path "$exe" --argjson argv "$argv" \
    '{execute: "guest-exec", arguments: {path: $path, arg: $argv, "capture-output": true}}')
  pid=$(_agent "$req" | jq -er '.return.pid') || winvm_die 1 "guest-exec failed to start: $exe"
  started=$SECONDS
  while :; do
    status=$(_agent "$(jq -cn --argjson pid "$pid" '{execute: "guest-exec-status", arguments: {pid: $pid}}')")
    [[ $(jq -r '.return.exited' <<<"$status") == true ]] && break
    if ((SECONDS - started >= WINVM_EXEC_TIMEOUT)); then
      winvm_die 124 "guest command timed out after ${WINVM_EXEC_TIMEOUT}s: $exe"
    fi
    sleep "$WINVM_EXEC_POLL"
  done
  jq -r '.return["out-data"] // empty' <<<"$status" | base64 -d
  jq -r '.return["err-data"] // empty' <<<"$status" | base64 -d >&2
  return "$(jq -r '.return.exitcode // 0' <<<"$status")"
}

# winvm_ps <script-relative-path> [args...] — скрипт из C:\mimir\scripts\win-vm\guest.
winvm_ps() {
  local script=$1
  shift
  winvm_exec powershell.exe -NoProfile -ExecutionPolicy Bypass \
    -File "C:\\mimir\\scripts\\win-vm\\guest\\$script" "$@"
}

# winvm_sync — рабочее дерево → $WINVM_HOST_DIR/src (rsync, строго внутри src), затем robocopy в госте.
winvm_sync() {
  local root rc=0
  root=$(git rev-parse --show-toplevel) || winvm_die 1 "not inside a git working tree"
  mkdir -p "$WINVM_HOST_DIR/src" "$WINVM_HOST_DIR/out" "$WINVM_HOST_DIR/tmp"
  echo "Sync: working tree -> $WINVM_HOST_DIR/src" >&2
  (cd "$root" && rsync -a --delete --delete-excluded --filter=':- .gitignore' \
    --exclude=/.git --exclude=/mise.local.toml --exclude=build/ ./ "$WINVM_HOST_DIR/src/") \
    || winvm_die 1 "rsync failed"
  echo "Sync: robocopy in guest -> C:\\mimir" >&2
  # bootstrap: в C:\mimir скрипта может ещё не быть, поэтому запуск из шары
  winvm_exec powershell.exe -NoProfile -ExecutionPolicy Bypass \
    -File "$WINVM_SHARE_DRIVE\\mimir\\src\\scripts\\win-vm\\guest\\sync.ps1" || rc=$?
  ((rc == 0)) || winvm_die "$rc" "robocopy failed in guest (exit $rc)"
}

# winvm_app_name — mimir.appName из gradle.properties (литералом не дублируется).
winvm_app_name() {
  local name
  name=$(sed -n 's/^mimir\.appName=//p' "$_WINVM_LIB_DIR/../../gradle.properties" | tr -d '\r' | head -n1)
  [[ -n $name ]] || winvm_die 1 "mimir.appName not found in gradle.properties"
  printf '%s' "$name"
}

# Код 5, если предыдущий запрос ещё выполняется: state=running И процесс agent.ps1 жив.
# Устаревший running после падения агента не блокирует.
_winvm_check_busy() {
  local out="$WINVM_HOST_DIR/out" state procs action
  [[ -f $out/status.json ]] || return 0
  state=$(jq -r '.state // empty' "$out/status.json" 2>/dev/null || true)
  [[ $state == running ]] || return 0
  procs=$(winvm_exec powershell.exe -NoProfile -Command \
    "Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\" | Where-Object { \$_.ProcessId -ne \$PID -and \$_.CommandLine -like '*agent.ps1*' } | Select-Object -ExpandProperty ProcessId" \
    || true)
  [[ -n ${procs//[[:space:]]/} ]] || return 0
  action=$(jq -r '.action // "unknown"' "$out/request.json" 2>/dev/null || echo unknown)
  winvm_die 5 "Another win:* request is running ($action) — wait or run win:logs"
}

# winvm_request <action> [upgrade=false] — пишет request.json, запускает MimirDev, печатает id.
winvm_request() {
  local action=$1 upgrade=${2:-false} out="$WINVM_HOST_DIR/out" id app rc=0
  mkdir -p "$out"
  _winvm_check_busy
  app=$(winvm_app_name)
  id=$(cat /proc/sys/kernel/random/uuid)
  jq -cn --arg id "$id" --arg action "$action" --argjson upgrade "$upgrade" --arg app "$app" \
    '{id: $id, action: $action, upgrade: $upgrade, appName: $app}' >"$out/request.json.tmp"
  mv -f "$out/request.json.tmp" "$out/request.json"
  rm -f "$out/status.json"
  winvm_exec schtasks.exe /run /tn MimirDev >/dev/null || rc=$?
  ((rc == 0)) || winvm_die "$rc" "could not start task MimirDev (exit $rc) — run win:provision first"
  printf '%s\n' "$id"
}

# Лог(и) шага для отчёта об ошибке.
_winvm_step_logs() {
  local out="$WINVM_HOST_DIR/out"
  case "$1" in
    run) echo "$out/run.log $out/run.err.log" ;;
    build | prepare) echo "$out/build.log" ;;
    install | uninstall) echo "$out/msi.log" ;;
    *) echo "$out/$1.log" ;;
  esac
}

_winvm_report_failure() {
  local status=$1 step code log
  step=$(jq -r '.step // "agent"' "$status")
  code=$(jq -r '.exitCode // 1' "$status")
  ((code != 0)) || code=1
  for log in $(_winvm_step_logs "$step"); do
    [[ -s $log ]] || continue
    echo "--- last 40 lines of $(basename "$log") ---" >&2
    tail -n 40 "$log" >&2
  done
  winvm_die "$code" "step '$step' failed (exit code $code)"
}

# winvm_wait_status <id> <until-states-csv> <timeout-s> — ждёт статус нашего запроса.
winvm_wait_status() {
  local id=$1 until_states=$2 timeout=$3 status="$WINVM_HOST_DIR/out/status.json"
  local started=$SECONDS seen=0 state step last_step=""
  while :; do
    if [[ -f $status && $(jq -r '.id // empty' "$status" 2>/dev/null || true) == "$id" ]]; then
      seen=1
      state=$(jq -r '.state' "$status")
      step=$(jq -r '.step // empty' "$status")
      if [[ $step != "$last_step" ]]; then
        echo "Step: $step ($state)" >&2
        last_step=$step
      fi
      [[ $state == failed ]] && _winvm_report_failure "$status"
      [[ ,$until_states, == *",$state,"* ]] && return 0
    fi
    if ((seen == 0 && SECONDS - started >= WINVM_STATUS_TIMEOUT)); then
      winvm_die 4 "No user is logged in to Windows — log in via virt-manager and retry"
    fi
    if ((SECONDS - started >= timeout)); then
      winvm_die 124 "timed out after ${timeout}s waiting for the guest (state: ${state:-none})"
    fi
    sleep "$WINVM_STATUS_POLL"
  done
}
