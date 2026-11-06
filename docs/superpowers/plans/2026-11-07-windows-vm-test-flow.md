# Windows VM Test Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Команды `mise run win:*` на Arch-хосте синхронизируют дерево в VM `win11`, собирают и запускают desktop или ставят MSI в сессии пользователя Windows, с логами на хосте.

**Architecture:** Хостовый bash-CLI (`winvm.sh` + `lib.sh`) говорит с гостем двумя путями: служебные команды — `virsh qemu-agent-command` (`guest-exec`, SYSTEM), пользовательские действия — файл-запрос `request.json` на virtiofs + `schtasks /run /tn MimirDev`, которая запускает `agent.ps1` в интерактивной сессии; гость публикует `status.json` и логи туда же.

**Tech Stack:** bash, jq, rsync, virsh (libvirt 12.9), PowerShell 5.1 (встроенный в Windows 11), robocopy, schtasks/ScheduledTasks, msiexec, mise 2026.11.2.

**Spec:** `docs/superpowers/specs/2026-11-07-windows-vm-test-flow-design.md`

## Global Constraints

- Конфиг: `WINVM_NAME=win11`, `WINVM_URI=qemu:///system`, `WINVM_SHARE=/srv/Shared`, `WINVM_SHARE_DRIVE=Z:`; префикс `MIMIR_` не использовать.
- Пути: хост `$WINVM_SHARE/mimir/{src,out,tmp}` ↔ гость `Z:\mimir\{src,out,tmp}`; сборочный каталог гостя `C:\mimir`; инструменты гостя `C:\mimir-tools` (Ruling: вместо `%LOCALAPPDATA%` из спеки 5.1 — путь не зависит от того, из-под какого пользователя вызван provision; передаётся параметром `-ToolsDir`).
- robocopy: `/MIR /XD build .gradle .kotlin node_modules /XF mise.local.toml`; код ≥ 8 — ошибка.
- `status.json.state` ∈ `running | launched | ok | failed`; поля `id, state, step, exitCode, finishedAt`.
- `request.json`: `{"id": "<uuid>", "action": "run"|"msi"|"uninstall"|"prepare", "upgrade": bool, "appName": "<mimir.appName>"}`; `appName` читается из `gradle.properties`, не литералом.
- Коды выхода: 2 — VM не найдена (`VM <name> not found in <uri>`); 3 — агент не ответил за 120 с (`Guest agent in <name> is not responding — check qemu-guest-agent service`); 4 — `status.json` не обновился за 30 с после `schtasks /run` (`No user is logged in to Windows — log in via virt-manager and retry`); 5 — запрос уже выполняется (`Another win:* request is running (<action>) — wait or run win:logs`); иначе — код процесса + последние 40 строк лога.
- Ошибки без стектрейсов; `set -euo pipefail`; все `win:*` в `mise.toml` имеют `run_windows`, печатающий `win:* — только на Linux-хосте с libvirt` с кодом 1.
- `mise.local.toml` не попадает в `$WINVM_SHARE/mimir/src`; копируется только по `--with-secrets` через `$WINVM_SHARE/mimir/tmp`, удаляется сразу.
- Сборка в госте только на `C:\mimir`, не на virtiofs.

## Review Focus

- Пробел или кириллица в пути/имени файла рабочего дерева — rsync списка и robocopy не должны ломаться (тест в Task 2).
- Устаревший `status.json` от прошлого запроса с `state: running`, но другим `id` и `finishedAt` — после падения агента не должен вечно блокировать (код 5 только если процесс `agent.ps1` жив; тест в Task 3).
- В `/srv/Shared` лежат чужие файлы — `rsync --delete` обязан работать строго внутри `$WINVM_SHARE/mimir/src` (тест в Task 2: файл-сосед в `$WINVM_SHARE` остаётся).
- Повторный `win:msi` с той же версией — не ошибка 1638 (ручная приёмка Task 7, логика удаления в Task 5).
- `win:reset` без `--yes` ничего не трогает (тест в Task 6).

---

### Task 1: Каркас CLI, фейк virsh, `win:test` в `ci:fast`

**Files:**
- Create: `scripts/win-vm/lib.sh`, `scripts/win-vm/winvm.sh`, `scripts/win-vm/tests/run.sh`, `scripts/win-vm/tests/fakes/virsh`, `scripts/win-vm/tests/test_lib.sh`
- Modify: `mise.toml` (`[tools] jq = "1.8.2"`; задачи `win:test` и `win:*`; `ci:fast` → `depends` добавить `win:test`)

**Interfaces:**
- Produces (`lib.sh`, подключается `source`):
  - `winvm_die <code> <message>` — печатает в stderr `winvm: <message>`, `exit <code>`.
  - `winvm_config` — выставляет дефолты `WINVM_*`, `WINVM_HOST_DIR="$WINVM_SHARE/mimir"`, `WINVM_GUEST_DIR="$WINVM_SHARE_DRIVE\\mimir"`.
  - `winvm_ensure_running` — `virsh dominfo` → нет → код 2; не `running` → `virsh start`; опрос `guest-ping` каждые 2 с до `WINVM_AGENT_TIMEOUT` (деф. 120) → код 3.
  - `winvm_exec <exe> [args...]` — `guest-exec` с `capture-output`, опрос `guest-exec-status` раз в 1 с до `exited`, печатает декодированный `out-data` в stdout, `err-data` в stderr, возвращает `exitcode`.
  - `winvm_ps <script-relative-path> [args...]` — `winvm_exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\mimir\scripts\win-vm\guest\<path>" args...`.
- Фейк `virsh`: пишет каждый вызов строкой в `$FAKE_VIRSH_LOG`; ответы: `dominfo` → `State: $FAKE_VM_STATE` (или код 1 при `FAKE_VM_STATE=missing`); `guest-ping` → `{"return":{}}` или ошибка при `FAKE_AGENT=down`; `guest-exec` → `{"return":{"pid":42}}`; `guest-exec-status` → `exited:true`, `exitcode:$FAKE_EXIT` (деф. 0), `out-data` = base64 `$FAKE_OUT`. Хук `FAKE_ON_EXEC` — путь к скрипту, вызываемому с JSON аргумента `guest-exec` (последующие задачи эмулируют гостя).
- `tests/run.sh` — запускает все `tests/test_*.sh`, каждый тест — функция `test_*`, в `mktemp -d` с `PATH="$fakes:$PATH"`, `WINVM_SHARE=$tmp/share`, `WINVM_AGENT_TIMEOUT=2`; итог `N passed, M failed`, код ≠ 0 при падениях; в конце `shellcheck` по `scripts/win-vm/*.sh scripts/win-vm/tests/*.sh scripts/win-vm/tests/fakes/virsh`.

- [ ] **Step 1: Написать падающие тесты `tests/test_lib.sh`**
  - `test_missing_vm_exits_2`: `FAKE_VM_STATE=missing`, `winvm_ensure_running` → код 2, stderr содержит `VM win11 not found in qemu:///system`.
  - `test_shut_off_vm_is_started`: `FAKE_VM_STATE="shut off"` → в логе фейка есть `start win11`.
  - `test_dead_agent_exits_3`: `FAKE_AGENT=down` → код 3, `Guest agent in win11 is not responding`.
  - `test_exec_returns_guest_exit_code_and_output`: `FAKE_EXIT=7 FAKE_OUT=hello`, `winvm_exec cmd.exe /c x` → stdout `hello`, код 7.
- [ ] **Step 2: `bash scripts/win-vm/tests/run.sh` → FAIL (нет `lib.sh`)**
- [ ] **Step 3: Реализовать `lib.sh`, фейк `virsh`, `run.sh`; `winvm.sh` — диспетчер `case "$1"` с `usage` (подкоманды Global Constraints, неизвестная → код 64) и `source lib.sh` по `$(dirname "$0")`**
- [ ] **Step 4: Добавить в `mise.toml`** `win:test` (`run = "bash scripts/win-vm/tests/run.sh"`) и `win:provision|sync|run|msi|uninstall|logs|snapshot|reset` (`run = "bash scripts/win-vm/winvm.sh <cmd>"`; mise дописывает хвостовые аргументы к команде, поэтому `mise run win:msi --upgrade` доходит до скрипта); у всех `run_windows` из Global Constraints; `ci:fast.depends` += `win:test`; `jq = "1.8.2"` в `[tools]`.
- [ ] **Step 5: `mise run win:test` → `4 passed, 0 failed`, shellcheck без замечаний; `WINVM_NAME=nope mise run win:msi --upgrade` → код 2**
- [ ] **Step 6: Commit** `feat(tools): add win-vm host CLI skeleton with guest-agent helpers`

### Task 2: `win:sync` — дерево на virtiofs и robocopy в `C:\mimir`

**Files:**
- Create: `scripts/win-vm/guest/sync.ps1`, `scripts/win-vm/tests/test_sync.sh`
- Modify: `scripts/win-vm/lib.sh`, `scripts/win-vm/winvm.sh`

**Interfaces:**
- Consumes: `winvm_config`, `winvm_ensure_running`, `winvm_exec` (Task 1).
- Produces: `winvm_sync` — (1) `rsync -a --delete --delete-excluded --filter=':- .gitignore' --exclude=/.git --exclude=/mise.local.toml --exclude=build/ ./ "$WINVM_HOST_DIR/src/"` из корня репозитория (dir-merge `.gitignore` учитывает вложенные `.gitignore`; глобальный git ignore не нужен — секреты исключены явно); (2) bootstrap: `sync.ps1` вызывается **из `Z:\mimir\src\scripts\win-vm\guest\sync.ps1`** (в `C:\mimir` его ещё может не быть) через `winvm_exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$WINVM_SHARE_DRIVE\mimir\src\scripts\win-vm\guest\sync.ps1"`; код ≠ 0 → `winvm_die` с кодом процесса.
- `sync.ps1 -Source Z:\mimir\src -Dest C:\mimir`: robocopy с флагами из Global Constraints + `/NFL /NDL /NJH /NP`; `exit 0` при коде < 8, иначе `exit $LASTEXITCODE`.

- [ ] **Step 1: Падающие тесты** (временный git-репозиторий как рабочее дерево):
  - `test_sync_copies_tracked_and_untracked_skips_secrets_and_build`: в репо `a.txt` (tracked), `new.txt` (untracked), `mise.local.toml`, `build/x` (в `.gitignore`) → в `$WINVM_SHARE/mimir/src` есть `a.txt`, `new.txt`, нет `mise.local.toml`, нет `build/`.
  - `test_sync_deletes_removed_files_only_inside_src`: файл удалён из дерева → пропал в `src`; `$WINVM_SHARE/neighbour.rar` на месте.
  - `test_sync_handles_spaces_and_cyrillic`: `docs/Заметка 1.md` доезжает.
  - `test_sync_runs_guest_robocopy_from_share`: лог фейка содержит `Z:\\mimir\\src\\scripts\\win-vm\\guest\\sync.ps1`.
  - `test_sync_fails_on_guest_error`: `FAKE_EXIT=8` → код 8.
- [ ] **Step 2: `mise run win:test` → новые тесты FAIL**
- [ ] **Step 3: Реализовать `winvm_sync`, подкоманду `sync`, `sync.ps1`**
- [ ] **Step 4: `mise run win:test` → все PASS**
- [ ] **Step 5: Commit** `feat(tools): sync working tree into Windows VM via virtiofs and robocopy`

### Task 3: Протокол request/status, `agent.ps1`, `win:run`

**Files:**
- Create: `scripts/win-vm/guest/agent.ps1`, `scripts/win-vm/tests/test_run.sh`, `scripts/win-vm/tests/fakes/guest-agent.sh` (эмуляция гостя для `FAKE_ON_EXEC`)
- Modify: `scripts/win-vm/lib.sh`, `scripts/win-vm/winvm.sh`

**Interfaces:**
- Consumes: `winvm_sync`, `winvm_exec` (Tasks 1–2).
- Produces:
  - `winvm_app_name` — значение `mimir.appName` из `gradle.properties`.
  - `winvm_request <action> [upgrade=false]` — проверка занятости (ниже), запись `request.json` атомарно (`tmp` + `mv`), удаление старого `status.json`, `winvm_exec schtasks.exe /run /tn MimirDev`, печать `id`.
  - `winvm_wait_status <id> <until-states-csv> <timeout-s>` — опрос `status.json` раз в 1 с; нет файла с этим `id` за 30 с → код 4; `state=failed` → печать `tail -n 40` лога из `step` и `exit exitCode`; достигнут один из `until` → 0.
  - Занятость: `status.json.state=running` с чужим `id` **и** `winvm_exec powershell -Command "Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\" | ? CommandLine -like '*agent.ps1*'"` вернул процесс → код 5; иначе статус считается устаревшим и перезаписывается.
- `agent.ps1 -Out Z:\mimir\out -Root C:\mimir`: читает `request.json`, функция `Set-Status($state,$step,$code)` (атомарная запись), действие `run`: `Get-CimInstance Win32_Process` → `Stop-Process` для java с `dev.mimir.desktop` в CommandLine; `Start-Process mise -ArgumentList 'exec','--','gradlew.bat',':apps:desktop:run' -WorkingDirectory C:\mimir -RedirectStandardOutput run.log -RedirectStandardError run.err.log -PassThru` (Ruling: PowerShell 5.1 не пишет оба потока в один файл через `Start-Process`; `win:logs run` хвостит оба); `running` пока в `run.log` нет строки `> Task :apps:desktop:run` и процесс жив; затем `launched`; после выхода — `ok` (код 0) или `failed`.
- `winvm.sh run`: `winvm_ensure_running` → `winvm_sync` → `winvm_request run` → `winvm_wait_status <id> launched,ok 1800` → `Mimir запущен в VM — смотрите в virt-manager` → `exec tail -F run.log run.err.log`.

- [ ] **Step 1: Падающие тесты** (`FAKE_ON_EXEC=fakes/guest-agent.sh`, сценарий через `FAKE_GUEST_SCENARIO`):
  - `test_request_contains_action_id_and_app_name`: `request.json` → `action=run`, `appName=Mimir`, `id` — uuid.
  - `test_run_reports_launched`: сценарий `launched` → stdout содержит `смотрите в virt-manager` (tail отключается `WINVM_NO_TAIL=1`).
  - `test_no_status_means_no_user_session_exit_4`: сценарий `silent`, `WINVM_STATUS_TIMEOUT=2` → код 4 и текст из Global Constraints.
  - `test_failed_build_prints_log_tail_and_code`: сценарий `failed` (`exitCode=1`, `run.log` 50 строк) → код 1, в stderr последние 40 строк.
  - `test_busy_agent_exits_5`: `status.json running` с чужим id, фейк сообщает живой `agent.ps1` → код 5.
  - `test_stale_running_status_is_overwritten`: то же, процесса нет → запрос уходит.
- [ ] **Step 2: `mise run win:test` → новые FAIL**
- [ ] **Step 3: Реализовать функции `lib.sh`, подкоманду `run`, `agent.ps1` (действие `run`, каркас диспетчера `switch ($req.action)` с `default { Set-Status failed ... 64 }`)**
- [ ] **Step 4: `mise run win:test` → все PASS**
- [ ] **Step 5: Commit** `feat(tools): run desktop app in Windows VM user session via MimirDev task`

### Task 4: `win:provision [--with-secrets]`

**Files:**
- Create: `scripts/win-vm/guest/provision.ps1`, `scripts/win-vm/tests/test_provision.sh`
- Modify: `scripts/win-vm/lib.sh`, `scripts/win-vm/winvm.sh`, `scripts/win-vm/guest/agent.ps1` (действие `prepare`)

**Interfaces:**
- Consumes: `winvm_sync`, `winvm_ps`, `winvm_request`, `winvm_wait_status`.
- Produces:
  - `provision.ps1 -ToolsDir C:\mimir-tools -MiseVersion 2026.11.2` (SYSTEM): скачивает `https://github.com/jdx/mise/releases/download/v<ver>/mise-v<ver>-windows-x64.zip`, распаковывает в `ToolsDir\mise`, добавляет `ToolsDir\mise\bin` в машинный `Path` (идемпотентно); определяет интерактивного пользователя (`(Get-CimInstance Win32_ComputerSystem).UserName`, пусто → `exit 4`); `Register-ScheduledTask MimirDev -Force` с `New-ScheduledTaskAction powershell.exe -WindowStyle Hidden -NoProfile -ExecutionPolicy Bypass -File C:\mimir\scripts\win-vm\guest\agent.ps1 -Out Z:\mimir\out -Root C:\mimir` и `New-ScheduledTaskPrincipal -UserId <user> -LogonType Interactive -RunLevel Limited`, без триггеров.
  - Действие `prepare` в `agent.ps1`: `mise trust C:\mimir\mise.toml`, `mise install java`, `mise exec -- gradlew.bat --version` → `build.log`; `ok`/`failed`.
  - `winvm.sh provision [--with-secrets]`: ensure → sync → `winvm_ps provision.ps1 ...` (код 4 → текст из Global Constraints) → при `--with-secrets`: `install -m600 mise.local.toml "$WINVM_HOST_DIR/tmp/"`, `winvm_exec cmd.exe /c move /Y Z:\mimir\tmp\mise.local.toml C:\mimir\mise.local.toml`, на хосте `trap` удаляет tmp-копию в любом случае → `winvm_request prepare` → `winvm_wait_status ok 3600` → `Подготовка завершена. Создайте снапшот: mise run win:snapshot`.
  - `WINVM_MISE_VERSION` (деф. `2026.11.2`) в `winvm_config`.

- [ ] **Step 1: Падающие тесты**: `test_provision_passes_tools_dir_and_mise_version`; `test_provision_without_flag_never_copies_secrets` (в `share` нет `mise.local.toml` ни на одном этапе — фейк-гость проверяет наличие файла при каждом exec и пишет в лог); `test_with_secrets_removes_tmp_copy_even_on_failure` (`FAKE_EXIT=1` на `move`); `test_provision_no_user_exits_4`.
- [ ] **Step 2: `mise run win:test` → FAIL**
- [ ] **Step 3: Реализовать**
- [ ] **Step 4: `mise run win:test` → PASS**
- [ ] **Step 5: Ручная проверка на VM (с разрешения пользователя — VM живая):** `mise run win:provision -- --with-secrets` → `Подготовка завершена`; в VM `schtasks /query /tn MimirDev` есть; `C:\mimir\mise.local.toml` есть, `/srv/Shared/mimir/tmp` пуст.
- [ ] **Step 6: Commit** `feat(tools): provision Windows VM with mise, JDK and MimirDev task`

### Task 5: `win:msi [--upgrade]` и `win:uninstall`

**Files:**
- Create: `scripts/win-vm/tests/test_msi.sh`
- Modify: `scripts/win-vm/guest/agent.ps1`, `scripts/win-vm/winvm.sh`

**Interfaces:**
- Consumes: `winvm_request <action> <upgrade>`, `winvm_wait_status`.
- Produces (в `agent.ps1`):
  - `Uninstall-Mimir($appName)` — ключи `HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*` с `DisplayName -eq $appName`; для каждого `msiexec /x <PSChildName> /qn /l*v Z:\mimir\out\msi-uninstall.log` (ждать `-Wait`); нет ключа — не ошибка; затем отчёт в `msi.log`: наличие ярлыков `"$env:APPDATA\Microsoft\Windows\Start Menu\Programs\$appName*.lnk"` и рабочего стола, ключа `HKCU:\Software\Classes\mimir`, `InstallLocation`.
  - Действие `msi`: `step=build` → `mise exec -- gradlew.bat :apps:desktop:packageMsi` в `build.log`; если не `upgrade` → `Uninstall-Mimir`; `step=install` → `msiexec /i <единственный *.msi из apps\desktop\build\compose\binaries\main\msi> /qn /l*v Z:\mimir\out\msi.log`, коды 0 и 3010 — успех; запуск `Start-Process` ярлыка меню «Пуск»; `ok`.
  - Действие `uninstall`: `Uninstall-Mimir` + отчёт, `ok`.
  - `winvm.sh msi [--upgrade]` → ensure, sync, `winvm_request msi <bool>`, `winvm_wait_status ok 3600`, печать `MSI установлен и запущен — смотрите в virt-manager`; `winvm.sh uninstall` → `winvm_request uninstall`, ожидание, печать отчёта из `msi.log`.

- [ ] **Step 1: Падающие тесты** (хост): `test_msi_request_upgrade_flag` (`--upgrade` → `"upgrade": true`, без флага → `false`); `test_msi_failed_build_tails_build_log` (сценарий `failed`, `step=build`); `test_uninstall_prints_report` (сценарий `ok`, `msi.log` содержит отчёт → выведен).
- [ ] **Step 2: `mise run win:test` → FAIL**
- [ ] **Step 3: Реализовать**
- [ ] **Step 4: `mise run win:test` → PASS**
- [ ] **Step 5: Commit** `feat(tools): build, install and uninstall MSI in Windows VM`

### Task 6: `win:logs`, `win:snapshot`, `win:reset`

**Files:**
- Create: `scripts/win-vm/tests/test_snapshot.sh`
- Modify: `scripts/win-vm/lib.sh`, `scripts/win-vm/winvm.sh`

**Interfaces:**
- Produces:
  - `winvm.sh logs [build|run|msi]` (деф. `run`; `run` → оба `run.log run.err.log`) → `exec tail -F`; неизвестный → код 64.
  - `winvm_nvram_path` — из `virsh dumpxml` (`<nvram ...>` текст).
  - `winvm.sh snapshot`: VM `running` → `virsh shutdown` и ожидание `shut off` до 180 с; `virsh snapshot-create-as $WINVM_NAME provisioned --disk-only --atomic`; `sudo cp <nvram> <nvram>.provisioned` (файл принадлежит root; при отказе sudo — понятная ошибка); снапшот `provisioned` уже есть → код 6 `Snapshot provisioned already exists — delete it with virsh snapshot-delete first`.
  - `winvm.sh reset [--yes]`: без `--yes` → печать `This discards everything in <name> since snapshot provisioned. Re-run with --yes`, код 1, **ни одного** вызова virsh кроме чтения; с `--yes` → shutdown (или `destroy` через 180 с), `virsh snapshot-revert $WINVM_NAME provisioned`, `sudo cp <nvram>.provisioned <nvram>`, `virsh start`, ожидание агента.

- [ ] **Step 1: Спайк на живой VM (до кода; спросить пользователя — VM выключится):** создать пробный `--disk-only` снапшот `probe` на выключенной VM, записать файл в гостя, `snapshot-revert probe`, убедиться что файла нет и Windows грузится (Secure Boot, TPM); удалить `probe` (`snapshot-delete --metadata` + слияние `blockcommit`/удаление overlay по выводу virsh). Если откат не работает — записать `Ruling:` в `.superpowers/sdd/2026-11-07-windows-vm-test-flow/progress.md` и реализовать запасной путь спеки 5.7 (`qemu-img convert` копия `win11.qcow2` + NVRAM в `/var/lib/libvirt/images/win11-provisioned.*`, откат копированием) с тем же CLI.
- [ ] **Step 2: Падающие тесты**: `test_reset_without_yes_touches_nothing` (лог фейка: только `dominfo`/`dumpxml`); `test_reset_with_yes_reverts_and_starts` (порядок: `shutdown`, `snapshot-revert win11 provisioned`, `start`; `sudo` подменён фейком); `test_snapshot_exists_exits_6`; `test_logs_unknown_target_exits_64`.
- [ ] **Step 3: `mise run win:test` → FAIL**
- [ ] **Step 4: Реализовать**
- [ ] **Step 5: `mise run win:test` → PASS**
- [ ] **Step 6: Commit** `feat(tools): add logs, snapshot and reset commands for Windows VM`

### Task 7: Документация и ручная приёмка

**Files:**
- Modify: `AGENTS.md:7` (раздел «Среда»), `.agents/skills/mimir-build-verify/SKILL.md` (новый раздел после «Команды»), `AGENTS.md` таблица «Карта кода» (строка `Windows-VM` → `scripts/win-vm/`)

- [ ] **Step 1: `AGENTS.md:7`** — после первого предложения дописать: «Исключение — одноразовая VM `win11` (virt-manager) со снапшотом `provisioned`: в неё можно ставить mise/JDK и собирать через `mise run win:*` (`scripts/win-vm/`, спека `docs/superpowers/specs/2026-11-07-windows-vm-test-flow-design.md`). MSI и GUI на Windows проверяются там до CI.» Фразу «Windows-специфичное (MSI) проверяется только в CI» заменить на «…в CI и в VM `win11`».
- [ ] **Step 2: Скилл `mimir-build-verify`** — раздел «Проверка на Windows-VM»: когда (`PlatformWindowStyler`, трей, выбор папки, `mimir://`, упаковка) → `mise run win:run` / `win:msi`; предусловие — вход в Windows в virt-manager (код 4); логи `mise run win:logs`; первый `win:msi` долгий; чистая система — `win:reset -- --yes`.
- [ ] **Step 3: `mise run skills:check && mise run win:test`** → без ошибок.
- [ ] **Step 4: Ручная приёмка по спеке §9 (пользователь залогинен в VM, разрешение получено):** `win:snapshot` → `win:run` (окно видно, `run.log` растёт) → правка строки в `libs/ui/.../SyncTexts` → `win:run` (быстрее первого; время обоих записать в отчёт) → откат правки → `win:msi` → запуск с ярлыка → повторный `win:msi` (нет 1638) → `win:uninstall` (ярлыков нет) → `win:reset -- --yes` (VM грузится, `mise --version` в госте работает). Ошибочные: выйти из Windows → `win:run` → код 4; выключить VM → `win:run` → автозапуск.
- [ ] **Step 5: `rtk context-init check --compact`** → при дрейфе `context-init update` и включить `.context-init/knowledge/` в коммит.
- [ ] **Step 6: Commit** `docs: document Windows VM test flow in AGENTS.md and build-verify skill`
