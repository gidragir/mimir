#!/usr/bin/env bash
# Фейк-гость для FAKE_ON_EXEC: получает JSON аргументов guest-exec ({path, arg[]}), эмулирует поведение гостя.
# Сценарий — FAKE_GUEST_SCENARIO: launched | ok | failed | silent (деф.).
# FAKE_FAIL_ON, FAKE_SECRET_SEEN — см. ниже. FAKE_GUEST_STEP / FAKE_GUEST_EXIT — шаг и код для failed; FAKE_AGENT_PROCESS=alive — agent.ps1 «жив».
set -u
args=$1
path=$(jq -r .path <<<"$args")
joined=$(jq -r '.arg | join(" ")' <<<"$args")
out="$WINVM_SHARE/mimir/out"
printf '%s %s\n' "$path" "$joined" >>"${FAKE_GUEST_LOG:-/dev/null}"
# На каждом exec фиксируем, лежит ли секрет в шаре (тесты проверяют, что без --with-secrets — никогда).
[[ -e $WINVM_SHARE/mimir/tmp/mise.local.toml ]] && printf '%s\n' "$joined" >>"${FAKE_SECRET_SEEN:-/dev/null}"

write_status() { # state step code
  local id
  id=$(jq -r .id "$out/request.json")
  jq -cn --arg id "$id" --arg state "$1" --arg step "$2" --argjson code "$3" \
    '{id: $id, state: $state, step: $step, exitCode: $code}' >"$out/status.json"
}

case "$path $joined" in
  *Get-CimInstance*)
    [[ ${FAKE_AGENT_PROCESS:-none} == alive ]] && printf "%s\n" "powershell.exe -File agent.ps1"
    exit 0
    ;;
  schtasks.exe*/run*)
    case "${FAKE_GUEST_SCENARIO:-silent}" in
      launched) write_status launched run 0 ;;
      ok) write_status ok "${FAKE_GUEST_STEP:-run}" 0 ;;
      failed)
        step=${FAKE_GUEST_STEP:-run}
        seq 1 50 | sed 's/^/line /' >"$out/$step.log"
        write_status failed "$step" "${FAKE_GUEST_EXIT:-1}"
        ;;
      *) ;;
    esac
    exit 0
    ;;
esac
# прочие команды: общие FAKE_OUT / FAKE_EXIT, как у обычного фейка
# FAKE_FAIL_ON — подстрока команды, на которой применяются FAKE_OUT/FAKE_EXIT (по умолчанию на всех).
if [[ -n ${FAKE_FAIL_ON:-} && "$path $joined" != *"$FAKE_FAIL_ON"* ]]; then exit 0; fi
[[ -n ${FAKE_OUT:-} ]] && echo "$FAKE_OUT"
exit "${FAKE_EXIT:-0}"
