#!/usr/bin/env bash
# Запуск всех tests/test_*.sh: каждая функция test_* — в своей подоболочке и tmp-каталоге.
set -uo pipefail
shopt -s nullglob

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../../.." && pwd)
passed=0
failed=0

run_one() {
  local file=$1 name=$2
  (
    set -euo pipefail
    TEST_TMP=$(mktemp -d)
    trap 'rm -rf "$TEST_TMP"' EXIT
    export TEST_TMP
    export PATH="$here/fakes:$PATH"
    export WINVM_SHARE="$TEST_TMP/share"
    export WINVM_AGENT_TIMEOUT=2 WINVM_POLL=0.1 WINVM_EXEC_POLL=0.05
    export FAKE_VIRSH_LOG="$TEST_TMP/virsh.log"
    export FAKE_VM_STATE=running
    mkdir -p "$WINVM_SHARE"
    : >"$FAKE_VIRSH_LOG"
    # shellcheck source=helpers.sh
    source "$here/helpers.sh"
    # shellcheck source=../lib.sh
    source "$root/scripts/win-vm/lib.sh"
    winvm_config
    # shellcheck disable=SC1090
    source "$file"
    "$name"
  )
}

for file in "$here"/test_*.sh; do
  [[ -e "$file" ]] || continue
  while read -r name; do
    if run_one "$file" "$name"; then
      passed=$((passed + 1))
      printf 'ok   %s\n' "$name"
    else
      failed=$((failed + 1))
      printf 'FAIL %s\n' "$name"
    fi
  done < <(grep -oE '^test_[A-Za-z0-9_]+' "$file")
done

printf '%d passed, %d failed\n' "$passed" "$failed"

if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -x --source-path=SCRIPTDIR "$root"/scripts/win-vm/*.sh "$here"/*.sh "$here"/fakes/virsh "$here"/fakes/*.sh \
    || failed=$((failed + 1))
else
  echo "shellcheck not found: skipped" >&2
fi

[[ $failed -eq 0 ]]
