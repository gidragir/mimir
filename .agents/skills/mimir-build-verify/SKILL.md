---
name: mimir-build-verify
description: Use when you need to build, test or verify a change in the Mimir repo, or when a Gradle/mise command is about to be run — covers the Arch-only rule, the right commands per module, the no-Android-SDK / no-secrets baseline, and local Windows testing in the win11 VM (win:run, win:msi, MSI, tray, window styler, mimir://, "проверить на Windows").
---

# Сборка и проверка Mimir

## Сначала — где мы

Команды выполняются **только на Arch Linux**. Если сессия на Windows и пользователь не разрешил иначе — ничего не запускать (ни gradle, ни mise, ни установку JDK): писать файлы и перечислить пользователю, что прогнать на Arch.

## Команды

| Что проверить | Команда |
|---|---|
| Вся сборка + тесты | `mise run build` |
| Все тесты | `mise run test` |
| Быстро: все JVM-тесты и build-logic без Wasm/JS/Android | `mise run test:fast` |
| Предпроверка перед push (токены + jactionlint + JVM-тесты) | `mise run ci:fast` |
| build-logic | `./gradlew -p build-logic test` |
| core / drive | `./gradlew :libs:core:allTests`, `./gradlew :libs:drive:allTests` (jvm, js, wasmJs; js/wasm — Karma + headless Chrome) |
| ui | `./gradlew :libs:ui:desktopTest`, `./gradlew :libs:ui:compileKotlinWasmJs` |
| desktop | `./gradlew :apps:desktop:test`, `:apps:desktop:packageUberJarForCurrentOS` |
| daemon | `./gradlew :apps:daemon:test`, затем `mise run daemon` и `curl -s 127.0.0.1:47821/health` |
| android | `./gradlew :apps:android:testDebugUnitTest`, `:apps:android:assembleDebug` |
| plugin / extension | `./gradlew :apps:obsidian-plugin:checkPlugin`, `:apps:browser-extension:checkExtension` |
| Скриншоты UI (desktop + extension) | `mise run screenshots` (headless-рендеринг в `build/reports/screenshots/`) |
| Граф зависимостей (`build.gradle.kts`) | `./gradlew buildHealth` (`com.autonomousapps.dependency-analysis`) |
| Дизайн-токены (`design/tokens.json`) | `node design/generate-tokens.mjs --check` |
| Yarn lock-файлы JS/Wasm (целостность путей) | `./gradlew kotlinNpmInstall kotlinWasmNpmInstall` (проверить отсутствие `kotlin-js-store/` в корне) |

Первый запуск на новой машине: `gradlew` создаётся командой `mise x gradle@9.8 -- gradle wrapper --gradle-version <9.x ≥ 9.8>`.

## Проверка на Windows-VM

Когда: правка `PlatformWindowStyler`, трея, выбора папки, `mimir://`, упаковки (MSI, лаунчер) — всё, что нельзя проверить на Linux. Одноразовая VM `win11` (virt-manager, qemu:///system), сборка идёт внутри неё; на реальный Windows-ПК по-прежнему ничего не ставится.

| Что | Команда |
|---|---|
| Окно приложения из текущего дерева | `mise run win:run` |
| MSI: сборка, установка, запуск с ярлыка | `mise run win:msi` (`--upgrade` — без удаления старой версии, проверка MajorUpgrade) |
| Удалить MSI и показать остатки | `mise run win:uninstall` |
| Логи (`build`, `run`, `msi`) | `mise run win:logs [build\|run\|msi]` |
| Чистая система | `mise run win:reset --yes` (стирает всё после снапшота `provisioned`) |
| Разовая подготовка / снапшот | `mise run win:provision --with-secrets`, затем `mise run win:snapshot` |

Порядок: Windows-специфичную правку сначала `mise run win:run`, MSI/лаунчер/ярлыки — `mise run win:msi`, и только потом CI. Рабочее дерево уходит в VM как есть (файлы из `git ls-files -co --exclude-standard`: коммитить и `git add` не нужно, игнорируемое и секреты не копируются).

Как читать поведение:
- `win:run` после запуска приложения остаётся в `tail -F` логов — это норма; для скриптов `WINVM_NO_TAIL=1`. Вывод самого приложения — `run.app.log` (задача `run` пишет его напрямую по `MIMIR_DEV_APP_LOG`): клиент Gradle на Windows отдаёт вывод JavaExec в `run.log` только после выхода приложения. Время: холодный (после `win:reset`) ≈ 8 мин, повторный без правок ≈ 6 с, после правки Kotlin ≈ 10 с.
- Новый `win:run` вытесняет старый: старое приложение убивается, шум `FAILED exit -1` в логах от прежнего запуска — не ошибка нового.
- Код 5 «Another win:* request is running» при зависшем агенте: убить `powershell.exe` с `agent.ps1` и `gradlew` в госте (или `win:reset --yes`); код 4 — войти в Windows в virt-manager и повторить.
- `virsh screenshot win11` даёт чёрный кадр: вид окна смотреть в virt-manager; на хосте проверяется только факт запуска (процесс `dev.mimir.desktop`).
- MSI регистрируется в HKLM, а `mimir://` и `mimir-ui.sock` остаются после удаления — это известные остатки, отчёт `win:uninstall` их показывает.
- Состояние гостя без экрана: `bash -c 'source scripts/win-vm/lib.sh; winvm_config; winvm_exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "Z:\\mimir\\tmp\\x.ps1"'` (скрипт кладётся в `/srv/Shared/mimir/tmp`; `lib.sh` — только из bash, в zsh `status` read-only; без `-ExecutionPolicy Bypass` скрипты запрещены). Так проверяются установленный cfg, процессы `Mimir.exe`, логи демона Gradle (`C:\Users\Admin\.gradle\daemon\*\daemon-*.out.log`).
- `mise run win:* | tail` скрывает код выхода: `set -o pipefail` или смотреть `out/status.json`.
- Правки `scripts/win-vm/guest/*.ps1` проверяются только живым прогоном (`win:test` — на фейковом `virsh`); `win:snapshot`/`win:reset` с `sudo` просят пароль только в интерактивном терминале.

Предусловие: войти в Windows в окне virt-manager (иначе код 4: `No user is logged in to Windows`). Первый `win:msi` долгий (Wasm/Node, WiX скачивается плагином Compose). Выключенную VM команды запускают сами. Тесты хостового CLI — `mise run win:test` (входит в `ci:fast`). Подробности: `docs/superpowers/specs/2026-11-07-windows-vm-test-flow-design.md`.

## Проверка трея и поповера на хосте (niri)

- Клик по иконке без мыши: `busctl --user call org.freedesktop.StatusNotifierItem-<pid>-1 /StatusNotifierItem org.kde.StatusNotifierItem Activate ii <x> <y>`; меню — `GetLayout` на `/MenuBar` (`com.canonical.dbusmenu`).
- Окна и фокус: `niri msg -j windows` (поповер на niri — отдельное плавающее окно), `niri msg -j event-stream`. Поведение фокуса и клавиш — временной трассировкой `System.err` в `TrayPopoverWindow`/`Main` (до коммита убрать): AWT-события на niri расходятся с экраном (`isShowing=true` у невидимого окна).
- Синтетический `Activate` ≠ реальный клик: noctalia шлёт свои координаты и фокус; итог подтверждает пользователь.
- Убить dev-экземпляр: `kill $(pgrep -f 'desktop\.MainK[t]')` — `pkill -f <шаблон>` совпадает с собственной командной строкой и убивает шелл.

## Обязательная базовая линия

1. **Без Android SDK** (`ANDROID_HOME`/`ANDROID_SDK_ROOT` не заданы, нет `sdk.dir` в `local.properties`): `mise run build` проходит, в выводе `Android SDK not found: skipping Android targets`. Обратите внимание: `local.properties` имеет приоритет над `-Pandroid.sdk.dir=`, поэтому для локальной проверки сборки без SDK необходимо временно переименовать `local.properties` (например, `mv local.properties local.properties.bak`).
2. **Без `mise.local.toml`**: сборка и тесты проходят без секретов.
3. **Смена переменных в `mise.local.toml`**: если изменялись `MIMIR_*` (OAuth-клиенты, пути, порты), а приложение запускается через `:run`, убедиться, что Gradle Daemon не держит старое окружение (`./gradlew --stop`).
4. **Все цели изменённого модуля**, а не только JVM: `allTests` для `libs/core`/`libs/drive`, `:libs:ui:compileKotlinWasmJs`, Android при наличии SDK. Ветвления по ОС покрыты юнит-тестами с подставленными `osName`/`env`; Windows и macOS проверяет CI-матрица — в отчёте сказать, что руками не проверено (`mimir-cross-platform`).
5. **Изменён UI** — визуальная проверка по `mimir-ui-ux` (скриншоты обеих тем и минимального размера окна).
6. Падение любого из пунктов — дефект, а не «особенность окружения».

### Проверка на Android эмуляторе и физическом устройстве

- **Сборка и установка**: `./gradlew :apps:android:assembleDebug && adb install -r apps/android/build/outputs/apk/debug/mimir-*-debug.apk`
- **Выдача разрешений для отладки без ручного UI**:
  `adb shell appops set dev.mimir.app MANAGE_EXTERNAL_STORAGE allow && adb shell pm grant dev.mimir.app android.permission.POST_NOTIFICATIONS`
- **Запуск Activity**: `adb shell am start -n dev.mimir.app/dev.mimir.android.MainActivity` (appId = `dev.mimir.app`)
- **Инспекция песочницы debug-сборки**: `adb shell run-as dev.mimir.app ls -la files shared_prefs`
- **Диагностика фоновой сети, WorkManager и крашей**:
  `adb logcat -b crash -d`, `adb logcat -b events -d`, `adb shell dumpsys netpolicy | rg 100`, `adb shell dumpsys jobscheduler`, `adb shell dumpsys notification --noredact`
- **Запуск эмулятора под Wayland (Niri / Sway)**: встроенный Qt SDK не имеет плагина wayland, запуск требует Xwayland:
  `QT_QPA_PLATFORM=xcb emulator -avd <avd_name> -netdelay none -netspeed full`

## CI и кроссплатформенная матрица

- **Выборочный запуск (`detect-changes`)**: `.github/workflows/ci.yml` использует `dorny/paths-filter@v4` для формирования динамической матрицы `os_matrix`. Изменения только в `apps/android/**` или `apps/obsidian-plugin/**` пропускают десктопную матрицу (`windows-latest`); изменения в `libs/**`, `build-logic/**`, `apps/desktop/**` или `apps/daemon/**` запускают `ubuntu-latest` и `windows-latest` (`macos-latest` исключён из автоматических PR-проверок и запускается через `workflow_dispatch` с `run_macos=true`). Везде первым идёт `./gradlew ciJvmTests`; затем на Linux `./gradlew build --continue`, на Windows — `packageMsi -Pmimir.bundleWebUi=true` только при `package_msi=true` (без повторного прогона общих KMP/JS-тестов); ночной `schedule` добавляет macOS, Arch и Docker dry-run, а на релизных PR `release-please--*` — быстрая валидация версии на `ubuntu-latest`.
- **Windows Runners**: На `windows-latest` обязателен экспорт `JAVA_HOME=$(mise where java | tr '\\' '/')` в `$GITHUB_ENV` и `$GITHUB_PATH`.
- **Karma в CI**: Chrome в headless-режиме на Ubuntu CI требует лаунчера `ChromeHeadlessCI` с флагами `--no-sandbox --disable-dev-shm-usage --disable-gpu --headless=new` (`apps/browser-extension/karma.config.d/headless.js`), а также таймаута mocha 120 с (`apps/browser-extension/karma.config.d/mocha-timeout.js`) из-за scrypt в JS.
- **Сплющивание dist в Release**: Перед расчетом контрольных сумм `dist/` очищается от вложенных папок во избежание ошибки `sha256sum: directory`.

## TDD

Тест пишется и запускается первым; отметить, что он упал по ожидаемой причине (нет класса/функции), и только потом реализация. Отчёт о проверке — с реальным выводом команды, не «должно пройти».
