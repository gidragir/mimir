#!/usr/bin/env bash
# Общие функции хостового CLI win-vm. Подключается через source.
# shellcheck shell=bash

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
  WINVM_HOST_DIR="$WINVM_SHARE/mimir"
  WINVM_GUEST_DIR="$WINVM_SHARE_DRIVE\\mimir"
  export WINVM_NAME WINVM_URI WINVM_SHARE WINVM_SHARE_DRIVE WINVM_AGENT_TIMEOUT \
    WINVM_POLL WINVM_EXEC_POLL WINVM_EXEC_TIMEOUT WINVM_HOST_DIR WINVM_GUEST_DIR
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
  local exe=$1 req pid status started
  shift
  req=$(jq -cn --arg path "$exe" \
    '{execute: "guest-exec", arguments: {path: $path, arg: $ARGS.positional, "capture-output": true}}' \
    --args "$@")
  pid=$(_agent "$req" | jq -r '.return.pid')
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
