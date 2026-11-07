# Mimir: флоу ручного тестирования на Windows 11 в VM (virt-manager)

**Дата:** 2026-11-07
**Статус:** Согласовано в brainstorming, ждёт ревью спеки
**Область:** dev-инструментарий (`scripts/win-vm/`, `mise.toml`), `AGENTS.md`, скилл `mimir-build-verify`. Код приложения не меняется.

## 1. Цель

Короткий цикл «правка на Arch → проверка на Windows» без push и ожидания CI. Покрываются два сценария, оба проверяются пользователем вручную в окне virt-manager; агент только готовит (синхронизирует, собирает, ставит, запускает):

1. **Desktop GUI** — свежая сборка `:apps:desktop` (окно, DWM-стилизация, трей-поповер, нативный выбор папки, `mimir://`).
2. **MSI** — сборка `packageMsi`, установка, MajorUpgrade, удаление, ярлыки, лаунчер (`win.norestart`, `.cfg`).

Вне области: JVM-тесты на Windows (остаются за CI), расширение в браузере VM, автоматическая визуальная проверка агентом.

**Критерий успеха:** после разовой подготовки одна команда на хосте (`mise run win:run` / `mise run win:msi`) доводит до видимого окна Mimir в VM; повторный цикл после правки — минуты (инкрементальная сборка, тёплый Gradle-демон в VM); каждая ошибка сообщается человеческим текстом и отдельным кодом выхода.

## 2. Исходное состояние (проверено 2026-11-07)

- Домен `win11`, `qemu:///system`, 16 ГБ / 12 vCPU, UEFI Secure Boot (OVMF, raw NVRAM `/var/lib/libvirt/qemu/nvram/win11_VARS.fd`), TPM, диск `/var/lib/libvirt/images/win11.qcow2`, libvirt 12.9.0. Снапшотов нет.
- `qemu-guest-agent` в госте работает (`guest-ping`, `guest-exec`), выполняет команды от `nt authority\system`.
- virtiofs: хост `/srv/Shared` → гость `host_share`, смонтирован как `Z:`. В шаре лежат посторонние файлы пользователя.
- В госте нет `java`, `git`, `mise`, `sshd`; `winget` в PATH SYSTEM отсутствует.
- Сеть: NAT `default`, гость `192.168.122.170`.

## 3. Выбранный подход

Сборка выполняется **внутри VM**: MSI (jpackage + WiX) собирается только на Windows, а `compose.desktop.currentOs` на хосте кладёт Linux-натив Skiko. Хост управляет VM через guest-agent; обмен данными — через virtiofs.

Отвергнуто: (B) Windows-jar с хоста + MSI из CI — правка сборки ради dev-сценария и 15–25 мин на MSI; (C) только CI-артефакты — каждая проверка требует push.

## 4. Архитектура

### 4.1 Компоненты

| Компонент | Где выполняется | Назначение |
|---|---|---|
| `scripts/win-vm/winvm.sh` | хост (bash) | CLI: `provision`, `sync`, `run`, `msi`, `uninstall`, `logs`, `reset`, `snapshot`. Обёртка над `virsh qemu-agent-command` (`guest-exec` + опрос `guest-exec-status`). |
| `scripts/win-vm/guest/provision.ps1` | гость, SYSTEM (через guest-agent) | Разовая подготовка: установка mise, `mise install` по `C:\mimir\mise.toml` (JDK 27), каталоги, регистрация задачи `MimirDev`. Идемпотентен. |
| `scripts/win-vm/guest/agent.ps1` | гость, интерактивный пользователь (задача `MimirDev`) | Читает `Z:\mimir\out\request.json`, выполняет действие, пишет `status.json` и логи. |
| `scripts/win-vm/guest/sync.ps1` | гость, SYSTEM | `robocopy Z:\mimir\src C:\mimir /MIR /XD build .gradle .kotlin node_modules /XF mise.local.toml`. |
| задачи `win:*` в `mise.toml` | хост | Делегируют в `winvm.sh`; `run_windows` печатает `win:* — только на Linux-хосте с libvirt` и выходит с кодом 1. |

Скрипты гостя живут в репозитории и попадают в VM тем же `sync` (`C:\mimir\scripts\win-vm\guest\`).

### 4.2 Разделение прав

guest-agent работает как SYSTEM в session 0: окно оттуда не видно пользователю, а per-user MSI установился бы в профиль SYSTEM. Поэтому:

- **guest-agent (SYSTEM)** — только служебное: `provision`, `sync` (robocopy), `schtasks /run /tn MimirDev`, чтение статуса, завершение процессов.
- **задача `MimirDev` (интерактивный пользователь, «только при входе пользователя», наивысшие права не нужны)** — всё, что связано с Gradle, GUI и `msiexec`. Gradle-кэш (`%USERPROFILE%\.gradle`) и демон принадлежат одному пользователю.

Предусловие: пользователь вошёл в Windows в окне virt-manager. Автологин не настраивается.

### 4.3 Каналы данных (virtiofs)

| Хост | Гость | Кто пишет | Содержимое |
|---|---|---|---|
| `$WINVM_SHARE/mimir/src` | `Z:\mimir\src` | хост (`rsync --delete`) | рабочее дерево: `git ls-files --cached --others --exclude-standard`, без `build/`, без `mise.local.toml` |
| `$WINVM_SHARE/mimir/out/request.json` | `Z:\mimir\out\request.json` | хост | `{"id": "<uuid>", "action": "run"\|"msi"\|"uninstall", "upgrade": false}` |
| `$WINVM_SHARE/mimir/out/status.json` | `Z:\mimir\out\status.json` | гость | `{"id", "state": "running"\|"launched"\|"ok"\|"failed", "step", "exitCode", "finishedAt"}` |
| `$WINVM_SHARE/mimir/out/{build,run,msi}.log` | `Z:\mimir\out\*.log` | гость | stdout+stderr Gradle, приложения (логи desktop идут в stderr), `msiexec /l*v` |

`win:logs` — `tail -F` по этим файлам на хосте, без обращения к агенту.

**Секреты:** `mise.local.toml` (OAuth-клиенты Google) через общую шару при каждом sync не передаётся. Он копируется в `C:\mimir\mise.local.toml` один раз командой `win:provision --with-secrets` через временный файл в `Z:\mimir\tmp\`, удаляемый сразу после копирования; robocopy его не трогает (`/XF`).

### 4.4 Конфигурация

Переменные окружения с значениями по умолчанию (переопределяются в `mise.local.toml`):

- `WINVM_NAME=win11`, `WINVM_URI=qemu:///system`, `WINVM_SHARE=/srv/Shared`, `WINVM_SHARE_DRIVE=Z:`.

Префикс `MIMIR_` не используется: такие переменные проброшены в `JavaExec` (`providers.environmentVariablesPrefixedBy("MIMIR_")`). Имя продукта для поиска в реестре `winvm.sh` читает из `mimir.appName` в `gradle.properties` и передаёт в `request.json`, литералом не дублируется.

## 5. Сценарии

### 5.1 `mise run win:provision [--with-secrets]` (разово)
1. VM выключена → `virsh start`, ожидание `guest-ping` до 120 с.
2. `sync` (см. 5.2).
3. guest-agent: `provision.ps1` — установка mise (официальный установщик в `%LOCALAPPDATA%` пользователя задачи; путь передаётся параметром), регистрация задачи `MimirDev` на текущего интерактивного пользователя (`query user` / `Win32_ComputerSystem.UserName`).
4. Через `MimirDev`: `mise trust` + `mise install java` в `C:\mimir`, прогрев `gradlew.bat --version`.
5. При `--with-secrets` — копирование `mise.local.toml`.
6. Подсказка: «Подготовка завершена. Создайте снапшот: `mise run win:snapshot`».

### 5.2 `mise run win:sync`
Источник — git toplevel каталога вызова: под `mise run` это `MISE_ORIGINAL_CWD` (mise выполняет задачу в каталоге своего `mise.toml`; из ворктри без задач `win:*` задача находится в родительском checkout, и без этого синхронизировался бы он). Дерево без `scripts/win-vm/guest/agent.ps1` отклоняется: `/MIR` стёр бы агента в госте. Вывод называет каталог, ветку, коммит и наличие незакоммиченных правок. Затем `rsync` списка файлов из git в `$WINVM_SHARE/mimir/src` и `sync.ps1` через guest-agent. Код robocopy ≥ 8 → ошибка.

### 5.3 `mise run win:run`
1. `sync`.
2. Занятый агент → код 5 до любых действий. Прежний запрос помечается вытесненным (новый id в `request.json`): прежний агент больше не пишет статус, прежний `win:run` печатает «superseded» и выходит с 0.
3. `stop.ps1` (тот же шаг у `win:msi`: иначе живой dev-экземпляр перехватывает запуск установленного через single-instance IPC) останавливает прежний Mimir (dev `dev.mimir.desktop` и установленный `<appName>.exe`) и ждёт выхода прежнего `agent.ps1` (до 60 с), чтобы его Gradle дописал «BUILD FAILED» в старый лог; хост печатает `Stopped previous Mimir in VM: pid …` или `No previous Mimir instance in VM`.
4. `run.log`/`run.err.log`/`run.app.log` → `run.prev.log`/`run.err.prev.log`/`run.app.prev.log` (`run.app.log` — вывод приложения мимо клиента Gradle, `MIMIR_DEV_APP_LOG`); `request.json {action: run}` → `schtasks /run /tn MimirDev`.
5. `agent.ps1`: `mise exec -- gradlew.bat :apps:desktop:run` с выводом в `run.log`; `status.json` = `running` на время сборки, `launched` после старта процесса приложения, `ok` после его штатного выхода, `failed` при ошибке сборки или ненулевом коде.
6. Хост ждёт `launched` (или `failed`), печатает «Mimir запущен в VM — смотрите в virt-manager» и хвост логов текущего запуска; когда приложение завершается, печатает `Mimir in VM exited with code N` и выходит с этим кодом (Ctrl-C прерывает просмотр, не приложение). Вывод JavaExec Gradle отдаёт в лог порциями, часто только по завершении приложения.

### 5.4 `mise run win:msi [--upgrade]`
1. `sync`.
2. `request.json {action: msi, upgrade}`.
3. `agent.ps1`: `gradlew.bat :apps:desktop:packageMsi` → `build.log` (задача упаковки сама включает `bundleWebUi`; первая сборка с Wasm/Node долгая).
4. Без `--upgrade`: удаление установленной версии — поиск в `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\*` по `DisplayName` = `mimir.appName`, `msiexec /x {ProductCode} /qn /l*v msi-uninstall.log`. Обходит ошибку 1638 при одинаковой версии.
5. `msiexec /i <apps/desktop/build/compose/binaries/main/msi/*.msi> /qn /l*v msi.log`; код 0/3010 = успех.
6. Запуск через установленный ярлык меню «Пуск» (проверяется сам лаунчер); stderr приложения при запуске через ярлык недоступен — это ожидаемо, логи смотреть через `win:run`.
7. С `--upgrade` шаг 4 пропускается: проверяется MajorUpgrade при повышенной версии (ответственность за повышение `mimir.version` локально — на пользователе, в коммит не попадает).

### 5.5 `mise run win:uninstall`
Шаг 4 из 5.4 и отчёт: остались ли ярлыки, `HKCU\Software\Classes\mimir`, каталог установки.

### 5.6 `mise run win:logs [build|run|msi]`
`tail -F` выбранного лога (по умолчанию `run.log`).

### 5.7 `mise run win:snapshot` / `mise run win:reset`
- У VM UEFI с raw NVRAM → внутренние qcow2-снапшоты недоступны. Используется **внешний disk-only снапшот** `provisioned` на выключенной VM (`virsh snapshot-create-as win11 provisioned --disk-only --atomic`) плюс копия NVRAM рядом.
- `win:reset` — выключение VM, откат к `provisioned` (`virsh snapshot-revert`, поддерживается для внешних снапшотов с libvirt 9.9), восстановление NVRAM, запуск. Требует подтверждения (`--yes`), так как стирает всё, сделанное в VM после снапшота.
- Работоспособность отката проверяется первым делом при реализации; если не работает — запасной вариант: `qemu-img` копия `win11.qcow2` + NVRAM в `/var/lib/libvirt/images/win11-provisioned.*`, откат копированием.

## 6. Ошибки и коды выхода

| Ситуация | Сообщение | Код |
|---|---|---|
| VM не найдена в `$WINVM_URI` | `VM <name> not found in <uri>` | 2 |
| guest-agent не ответил за 120 с | `Guest agent in <name> is not responding — check qemu-guest-agent service` | 3 |
| нет интерактивной сессии / `status.json` не обновился за 30 с после `schtasks /run` | `No user is logged in to Windows — log in via virt-manager and retry` | 4 |
| уже выполняется запрос (`status.json.state = running` и id не завершён) | `Another win:* request is running (<action>) — wait or run win:logs` | 5 |
| сборка/robocopy/msiexec упали | последние 40 строк соответствующего лога, затем код процесса | код процесса |

Ошибки печатаются без стектрейсов; `set -euo pipefail` в `winvm.sh`.

## 7. Изменения документации

- `AGENTS.md`, раздел «Среда»: исключение — одноразовая VM `win11` (virt-manager) с внешним снапшотом `provisioned` предназначена для тестов; в неё можно ставить mise/JDK и запускать Gradle через `mise run win:*`. Правило «на Windows-ПК пользователя ничего не устанавливать» для реального ПК сохраняется.
- Скилл `mimir-build-verify`: раздел «Проверка на Windows-VM» — когда запускать `win:run`/`win:msi`, предусловие входа в VM, где логи.

## 8. Инварианты Mimir

- **Платформы:** dev-инструмент работает только на Linux-хосте с libvirt и Windows-гостем с qemu-guest-agent и virtiofs. На прочих хостах `run_windows` / отсутствие `virsh` → понятная ошибка. Код приложения и его поведение на платформах не меняются.
- **Дизайн:** «план → исполнение»: хост формирует запрос (`request.json`), гость исполняет и публикует статус (`status.json`); файловый контракт — единственная связь, транспорт (guest-agent) заменяем. Эталон в проекте — `SyncPlanBuilder` → `SyncPlan` → `DefaultSyncEngine`.
- **IO:** Drive не затрагивается. Синхронизация инкрементальная (`rsync` + `robocopy /MIR`, только изменённые файлы); сборка на локальном диске гостя (`C:\mimir`), не на virtiofs; Gradle-кэш и демон в VM переиспользуются.
- **UX:** CLI: одна команда на сценарий, прогресс шагов в консоли, человеческие сообщения на каждую ошибку (раздел 6), явная подсказка «смотрите в virt-manager». GUI проверяется пользователем глазами.

## 9. Тестирование и приёмка

- `shellcheck scripts/win-vm/winvm.sh` (shellcheck закреплён в `mise.toml`); добавляется отдельным шагом в задачу `ci:fast`.
- Ручная приёмка на VM по порядку: `win:provision --with-secrets` → `win:snapshot` → `win:run` (окно Mimir видно, `run.log` пишется) → правка в `:libs:ui` → `win:run` (повтор быстрее первого) → `win:msi` → запуск с ярлыка → `win:msi` повторно с той же версией (нет ошибки 1638) → `win:uninstall` (ярлыки удалены) → `win:reset --yes` (VM чистая, инструменты на месте).
- Ошибочные пути: `win:run` при незалогиненном пользователе → код 4; при выключенной VM → автозапуск.

## 10. Риски

- Откат внешнего снапшота UEFI-VM может потребовать ручной работы с NVRAM — проверить первым (5.7).
- Установщик mise для Windows и `mise install` в госте требуют интернета в VM (NAT есть).
- Первая `packageMsi` в VM долгая (Wasm/Node/Binaryen, WiX скачивается плагином Compose).
- Задача `MimirDev` привязана к одному пользователю; смена пользователя — повторный `win:provision`.
