#!/usr/bin/env bash
# Запуск всех tests/test_*.sh: каждая функция test_* — в своей подоболочке и tmp-каталоге.
set -uo pipefail
shopt -s nullglob

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$here/../../.." && pwd)
passed=0
failed=0

# Шаблонный git-репозиторий создаётся один раз; make_repo копирует его через cp -a.
WINVM_TEST_TEMPLATE_REPO=$(mktemp -d)
trap 'rm -rf "$WINVM_TEST_TEMPLATE_REPO"' EXIT
export WINVM_TEST_TEMPLATE_REPO
(
  cd "$WINVM_TEST_TEMPLATE_REPO"
  mkdir -p build docs scripts/win-vm/guest
  git init -q -b main
  echo agent >scripts/win-vm/guest/agent.ps1
  echo tracked >a.txt
  echo secret >mise.local.toml
  echo out >build/x
  echo "build/" >.gitignore
  git add a.txt .gitignore scripts
  echo untracked >new.txt
)

run_one() {
  local file=$1 name=$2
  (
    set -euo pipefail
    TEST_TMP=$(mktemp -d)
    trap 'rm -rf "$TEST_TMP"' EXIT
    export TEST_TMP
    export WINVM_TEST_ROOT="$repo_root"
    export WINVM_STATUS_POLL=0.1
    export FAKE_ON_EXEC="$here/fakes/guest-agent.sh"
    export PATH="$here/fakes:$PATH"
    export WINVM_SHARE="$TEST_TMP/share"
    export WINVM_AGENT_TIMEOUT=2 WINVM_POLL=0.1 WINVM_EXEC_POLL=0.05
    export FAKE_VIRSH_LOG="$TEST_TMP/virsh.log"
    export FAKE_VM_STATE=running
    # mise run win:test выставляет их; иначе winvm_sync взял бы корень настоящего репозитория
    unset MISE_ORIGINAL_CWD MISE_TASK_NAME
    mkdir -p "$WINVM_SHARE"
    : >"$FAKE_VIRSH_LOG"
    # shellcheck source=helpers.sh
    source "$here/helpers.sh"
    # shellcheck source=../lib.sh
    source "$repo_root/scripts/win-vm/lib.sh"
    winvm_config
    # shellcheck disable=SC1090
    source "$file"
    "$name"
  )
}

for file in "$here"/test_*.sh; do
  [[ -e "$file" ]] || continue
  while read -r name; do
    # Не в условии `if`: иначе set -e внутри подоболочки отключается и падают только последние ассерты.
    run_one "$file" "$name"
    rc=$?
    if [[ $rc -eq 0 ]]; then
      passed=$((passed + 1))
      printf 'ok   %s\n' "$name"
    else
      failed=$((failed + 1))
      printf 'FAIL %s\n' "$name"
    fi
  done < <(rg -o '^test_[A-Za-z0-9_]+' "$file")
done

printf '%d passed, %d failed\n' "$passed" "$failed"

if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -x --source-path=SCRIPTDIR "$repo_root"/scripts/win-vm/*.sh "$here"/*.sh "$here"/fakes/virsh "$here"/fakes/*.sh \
    || failed=$((failed + 1))
else
  echo "shellcheck not found: skipped" >&2
fi

[[ $failed -eq 0 ]]
