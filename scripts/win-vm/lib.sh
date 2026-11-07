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
  : "${WINVM_MISE_VERSION:=2026.11.2}"
  : "${WINVM_STATUS_TIMEOUT:=30}"
  : "${WINVM_STATUS_POLL:=1}"
  WINVM_HOST_DIR="$WINVM_SHARE/mimir"
  WINVM_GUEST_DIR="$WINVM_SHARE_DRIVE\\mimir"
  export WINVM_NAME WINVM_URI WINVM_SHARE WINVM_SHARE_DRIVE WINVM_AGENT_TIMEOUT \
    WINVM_POLL WINVM_EXEC_POLL WINVM_EXEC_TIMEOUT WINVM_MISE_VERSION WINVM_STATUS_TIMEOUT WINVM_STATUS_POLL WINVM_HOST_DIR WINVM_GUEST_DIR
}

_virsh() { virsh -c "$WINVM_URI" "$@"; }

_agent() { _virsh qemu-agent-command "$WINVM_NAME" "$1"; }

# winvm_vm_state — состояние домена (running, shut off, ...); нет домена — код 2.
winvm_vm_state() {
  local info
  info=$(_virsh dominfo "$WINVM_NAME" 2>/dev/null) \
    || winvm_die 2 "VM $WINVM_NAME not found in $WINVM_URI"
  awk -F: '/^State:/ { sub(/^[ \t]+/, "", $2); print $2 }' <<<"$info"
}

# winvm_ensure_running — VM существует, запущена и гостевой агент отвечает.
winvm_ensure_running() {
  local state started
  state=$(winvm_vm_state) || exit $?
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
  local exe=$1 req pid status started arg_json='[]'
  shift
  # jq --args путает аргументы вида -NoProfile с собственными опциями, поэтому массив собираем через stdin
  if (($# > 0)); then arg_json=$(printf '%s\0' "$@" | jq -Rsc 'split("\u0000")[:-1]'); fi
  req=$(jq -cn --arg path "$exe" --argjson arg_json "$arg_json" \
    '{execute: "guest-exec", arguments: {path: $path, arg: $arg_json, "capture-output": true}}')
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

# Каталог, из которого пользователь вызвал команду. mise выполняет задачу в каталоге того mise.toml, где она
# объявлена: в ворктри без задач win:* (ветка старше win-vm) mise находит их в родительском checkout
# (.claude/worktrees вложены в него), и без MISE_ORIGINAL_CWD синхронизировался бы чужой корень.
_winvm_start_dir() {
  if [[ ${MISE_TASK_NAME:-} == win:* && -n ${MISE_ORIGINAL_CWD:-} ]]; then
    printf '%s' "$MISE_ORIGINAL_CWD"
  else
    printf '%s' "$PWD"
  fi
}

# winvm_source_root — git toplevel дерева, которое уходит в VM.
winvm_source_root() {
  git -C "$(_winvm_start_dir)" rev-parse --show-toplevel 2>/dev/null \
    || winvm_die 1 "not inside a git working tree: $(_winvm_start_dir)"
}

# Описание дерева для вывода: ветка, коммит, есть ли незакоммиченные правки.
_winvm_tree_label() {
  local root=$1 branch commit dirty=""
  branch=$(git -C "$root" symbolic-ref --short -q HEAD) || branch="detached HEAD"
  commit=$(git -C "$root" rev-parse --short -q --verify HEAD) || commit="none"
  [[ -z $(git -C "$root" status --porcelain 2>/dev/null) ]] || dirty=", uncommitted changes"
  printf 'branch %s, commit %s%s' "$branch" "$commit" "$dirty"
}

# winvm_sync — рабочее дерево → $WINVM_HOST_DIR/src (rsync, строго внутри src), затем robocopy в госте.
winvm_sync() {
  local root rc=0
  root=$(winvm_source_root) || exit $?
  # robocopy /MIR стёр бы в госте agent.ps1, и задача MimirDev перестала бы работать
  [[ -f $root/scripts/win-vm/guest/agent.ps1 ]] \
    || winvm_die 1 "$root has no scripts/win-vm ($(_winvm_tree_label "$root")) — rebase it onto main or run from a tree that has it"
  mkdir -p "$WINVM_HOST_DIR/src" "$WINVM_HOST_DIR/out" "$WINVM_HOST_DIR/tmp"
  echo "Sync: $root ($(_winvm_tree_label "$root")) -> $WINVM_HOST_DIR/src" >&2
  # Список файлов берём у git (--exclude-standard учитывает .gitignore, глобальный ignore и info/exclude), а не
  # фильтром rsync по .gitignore: иначе секреты из глобального ignore (.env, *.key) попали бы в VM.
  # Удалённые в рабочем дереве, но ещё не закоммиченные файлы пропускаем, чтобы rsync не падал.
  local list dest="$WINVM_HOST_DIR/src"
  list=$(mktemp)
  (cd "$root" && git ls-files -z -co --exclude-standard \
    | while IFS= read -r -d '' f; do
        [[ $f == mise.local.toml ]] || { [[ -e $f || -L $f ]] && printf '%s\0' "$f"; }
      done >"$list") || { rm -f "$list"; winvm_die 1 "git ls-files failed"; }
  # --files-from отключает --delete rsync, поэтому лишнее в src удаляем сами (строго внутри src)
  (cd "$dest" && find . -mindepth 1 \( -type f -o -type l \) -print0 | sed -z 's|^\./||' | LC_ALL=C sort -z \
    | LC_ALL=C comm -z -23 - <(LC_ALL=C sort -z "$list") | xargs -0 -r rm -f --) || { rm -f "$list"; winvm_die 1 "stale cleanup failed"; }
  find "$dest" -mindepth 1 -type d -empty -delete
  (cd "$root" && rsync -rptq --no-links --from0 --files-from="$list" ./ "$dest/") || { rm -f "$list"; winvm_die 1 "rsync failed"; }
  rm -f "$list"
  # rsync не удаляет в приёмнике симлинк, чьё имя совпадает с пропущенным симлинком источника
  find "$WINVM_HOST_DIR/src" -type l -delete
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

# winvm_stop_previous_app — до нового запуска останавливает прежний Mimir в госте (dev-запуск и установленный MSI)
# и ждёт, пока прежний агент и его Gradle допишут логи; пишет об этом строкой.
winvm_stop_previous_app() {
  local out line stopped=() timeout=0
  out=$(winvm_ps stop.ps1 -AppName "$(winvm_app_name)") || winvm_die 1 "could not stop the previous Mimir in the guest"
  while IFS= read -r line; do
    line=${line%$'\r'}
    case $line in
      stopped\ *) read -r _ pid name <<<"$line"; stopped+=("pid $pid ($name)") ;;
      wait-timeout) timeout=1 ;;
    esac
  done <<<"$out"
  if ((${#stopped[@]} == 0)); then
    echo "No previous Mimir instance in VM" >&2
  else
    local IFS=,
    echo "Stopped previous Mimir in VM: ${stopped[*]}" >&2
  fi
  ((timeout == 0)) || echo "warning: the previous win:* agent is still running; its output may reach the rotated logs" >&2
}

# winvm_supersede_request — помечает прежний запрос вытесненным до остановки его приложения: прежний агент
# пишет статус только для своего id, а прежний win:run, увидев новый id, сообщает, что его вытеснили.
winvm_supersede_request() {
  local out="$WINVM_HOST_DIR/out"
  mkdir -p "$out"
  jq -cn --arg id "$(cat /proc/sys/kernel/random/uuid)" '{id: $id, action: "superseded"}' >"$out/request.json.tmp"
  mv -f "$out/request.json.tmp" "$out/request.json"
  rm -f "$out/status.json"
}

# winvm_take_over — перед новым запуском приложения: занятый агент → код 5 до любых действий,
# затем прежний запрос вытесняется и прежний Mimir останавливается.
winvm_take_over() {
  _winvm_check_busy
  winvm_supersede_request
  winvm_stop_previous_app
}

# winvm_rotate_logs <name...> — <name>.log → <name>.prev.log, чтобы хвост показывал только текущий запуск.
winvm_rotate_logs() {
  local out="$WINVM_HOST_DIR/out" name
  mkdir -p "$out"
  for name in "$@"; do
    if [[ -e $out/$name.log ]]; then mv -f "$out/$name.log" "$out/$name.prev.log"; fi
  done
}

# winvm_follow_run <id> — хвост логов текущего запуска, пока приложение работает; код — код завершения Gradle.
winvm_follow_run() {
  local id=$1 out="$WINVM_HOST_DIR/out" tail_pid state code rc=0
  touch "$out/run.log" "$out/run.err.log" "$out/run.app.log"
  # stderr tail — шум ротации («has become inaccessible»), когда этот запуск вытесняют
  tail -F "$out/run.log" "$out/run.err.log" "$out/run.app.log" 2>/dev/null &
  tail_pid=$!
  # shellcheck disable=SC2064 # pid подставляется сразу
  trap "kill $tail_pid 2>/dev/null" EXIT
  while :; do
    if [[ $(jq -r '.id // empty' "$out/request.json" 2>/dev/null || true) != "$id" ]]; then
      sleep "$WINVM_STATUS_POLL"
      echo "This run was superseded by a newer win:* request" >&2
      break
    fi
    state=$(jq -r --arg id "$id" 'select(.id == $id) | .state' "$out/status.json" 2>/dev/null || true)
    if [[ $state == ok || $state == failed ]]; then
      code=$(jq -r '.exitCode // 1' "$out/status.json")
      sleep "$WINVM_STATUS_POLL"   # дать tail дочитать последние строки
      echo "Mimir in VM exited with code $code" >&2
      rc=$code
      break
    fi
    sleep "$WINVM_STATUS_POLL"
  done
  kill "$tail_pid" 2>/dev/null || true
  wait "$tail_pid" 2>/dev/null || true
  trap - EXIT
  return "$rc"
}

# Лог(и) шага для отчёта об ошибке.
_winvm_step_logs() {
  local out="$WINVM_HOST_DIR/out"
  case "$1" in
    run) echo "$out/run.log $out/run.err.log $out/run.app.log" ;;
    build | prepare) echo "$out/build.log" ;;
    install) echo "$out/msi.log" ;;
    uninstall) echo "$out/msi-uninstall.log" ;;
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

# winvm_secret_source — mise.local.toml из рабочего дерева, а в linked-worktree (секрет не в git) — из основного checkout.
winvm_secret_source() {
  local root main
  root=$(winvm_source_root) || exit $?
  if [[ -f $root/mise.local.toml ]]; then
    printf '%s' "$root/mise.local.toml"
    return 0
  fi
  main=$(dirname "$(git -C "$root" rev-parse --path-format=absolute --git-common-dir)")
  if [[ -f $main/mise.local.toml ]]; then
    printf '%s' "$main/mise.local.toml"
    return 0
  fi
  winvm_die 1 "mise.local.toml not found in $root or $main"
}

# winvm_nvram_path — путь к NVRAM домена из dumpxml.
winvm_nvram_path() {
  _virsh dumpxml "$WINVM_NAME" | sed -n 's|.*<nvram[^>]*>\([^<]*\)</nvram>.*|\1|p' | head -n1
}

winvm_snapshot_exists() {
  _virsh snapshot-list "$WINVM_NAME" --name 2>/dev/null | grep -qx 'provisioned'
}

# winvm_shutdown_wait — корректное выключение; через WINVM_SHUTDOWN_TIMEOUT (деф. 180) — destroy.
winvm_shutdown_wait() {
  local started=$SECONDS
  [[ $(winvm_vm_state) == running ]] || return 0
  echo "Shutting down VM $WINVM_NAME..." >&2
  _virsh shutdown "$WINVM_NAME" >/dev/null
  until [[ $(winvm_vm_state) == "shut off" ]]; do
    if ((SECONDS - started >= ${WINVM_SHUTDOWN_TIMEOUT:-180})); then
      echo "VM $WINVM_NAME did not shut down in ${WINVM_SHUTDOWN_TIMEOUT:-180}s — forcing off" >&2
      _virsh destroy "$WINVM_NAME" >/dev/null
      break
    fi
    sleep "$WINVM_POLL"
  done
}

# winvm_nvram_copy <from> <to> — best-effort: root-файл, sudo может спросить пароль или быть недоступен.
winvm_nvram_copy() {
  sudo cp "$1" "$2" || echo "warning: could not copy NVRAM ($1 -> $2); disk revert still works without it" >&2
}
