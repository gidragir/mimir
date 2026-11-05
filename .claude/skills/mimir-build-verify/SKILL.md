---
name: mimir-build-verify
description: Use when you need to build, test or verify a change in the Mimir repo, or when a Gradle/mise command is about to be run — covers the Arch-only rule, the right commands per module, and the no-Android-SDK / no-secrets baseline.
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
| Предпроверка перед push (токены + actionlint + JVM-тесты) | `mise run ci:fast` |
| build-logic | `./gradlew -p build-logic test` |
| core / drive | `./gradlew :libs:core:allTests`, `./gradlew :libs:drive:allTests` (jvm, js, wasmJs; js/wasm — Karma + headless Chrome) |
| ui | `./gradlew :libs:ui:desktopTest`, `./gradlew :libs:ui:compileKotlinWasmJs` |
| desktop | `./gradlew :apps:desktop:test`, `:apps:desktop:packageUberJarForCurrentOS` |
| daemon | `./gradlew :apps:daemon:test`, затем `mise run daemon` и `curl -s 127.0.0.1:47821/health` |
| android | `./gradlew :apps:android:testDebugUnitTest`, `:apps:android:assembleDebug` |
| plugin / extension | `./gradlew :apps:obsidian-plugin:checkPlugin`, `:apps:browser-extension:checkExtension` |
| Скриншоты UI (desktop + extension) | `mise run screenshots` (headless-рендеринг в `build/reports/screenshots/`) |
| Граф зависимостей (`build.gradle.kts`) | `./gradlew buildHealth` (`com.autonomousapps.dependency-analysis`) |
| Дизайн-токены (`design/tokens.json`) | `node scripts/generate-tokens.mjs --check` |

Первый запуск на новой машине: `gradlew` создаётся командой `mise x gradle@9.8 -- gradle wrapper --gradle-version <9.x ≥ 9.8>`.

## Обязательная базовая линия

1. **Без Android SDK** (`ANDROID_HOME`/`ANDROID_SDK_ROOT` не заданы, нет `sdk.dir` в `local.properties`): `mise run build` проходит, в выводе `Android SDK not found: skipping Android targets`. Обратите внимание: `local.properties` имеет приоритет над `-Pandroid.sdk.dir=`, поэтому для локальной проверки сборки без SDK необходимо временно переименовать `local.properties` (например, `mv local.properties local.properties.bak`).
2. **Без `mise.local.toml`**: сборка и тесты проходят без секретов.
3. **Смена переменных в `mise.local.toml`**: если изменялись `MIMIR_*` (OAuth-клиенты, пути, порты), а приложение запускается через `:run`, убедиться, что Gradle Daemon не держит старое окружение (`./gradlew --stop`).
4. **Все цели изменённого модуля**, а не только JVM: `allTests` для `libs/core`/`libs/drive`, `:libs:ui:compileKotlinWasmJs`, Android при наличии SDK. Ветвления по ОС покрыты юнит-тестами с подставленными `osName`/`env`; Windows и macOS проверяет CI-матрица — в отчёте сказать, что руками не проверено (`mimir-cross-platform`).
5. **Изменён UI** — визуальная проверка по `mimir-ui-ux` (скриншоты обеих тем и минимального размера окна).
6. Падение любого из пунктов — дефект, а не «особенность окружения».

### Проверка на Android эмуляторе

- **Сборка и установка**: `./gradlew :apps:android:assembleDebug && adb install -r apps/android/build/outputs/apk/debug/android-debug.apk`
- **Запуск Activity**: `adb shell am start -n dev.mimir.app/dev.mimir.android.MainActivity` (appId = `dev.mimir.app`)
- **Запуск эмулятора под Wayland (Niri / Sway)**: встроенный Qt SDK не имеет плагина wayland, запуск требует Xwayland:
  `QT_QPA_PLATFORM=xcb emulator -avd <avd_name> -netdelay none -netspeed full`

## CI и кроссплатформенная матрица

- **Выборочный запуск (`detect-changes`)**: `.github/workflows/ci.yml` использует `dorny/paths-filter@v4` для формирования динамической матрицы `os_matrix`. Изменения только в `apps/android/**` или `apps/obsidian-plugin/**` пропускают десктопную матрицу (`windows-latest`); изменения в `libs/**`, `build-logic/**`, `apps/desktop/**` или `apps/daemon/**` запускают `ubuntu-latest` и `windows-latest` (`macos-latest` исключён из автоматических PR-проверок и запускается через `workflow_dispatch` с `run_macos=true`). Везде первым идёт `./gradlew ciJvmTests`; затем на Linux `./gradlew build --continue`, на Windows — `packageMsi -Pmimir.bundleWebUi=true` только при `package_msi=true` (без повторного прогона общих KMP/JS-тестов); ночной `schedule` добавляет macOS, Arch и Docker dry-run, а на релизных PR `release-please--*` — быстрая валидация версии на `ubuntu-latest`.
- **Windows Runners**: На `windows-latest` обязателен экспорт `JAVA_HOME=$(mise where java | tr '\\' '/')` в `$GITHUB_ENV` и `$GITHUB_PATH`.
- **Karma в CI**: Chrome в headless-режиме на Ubuntu CI требует лаунчера `ChromeHeadlessCI` с флагами `--no-sandbox --disable-dev-shm-usage --disable-gpu --headless=new` (`apps/browser-extension/karma.config.d/headless.js`), а также таймаута mocha 120 с (`apps/browser-extension/karma.config.d/mocha-timeout.js`) из-за scrypt в JS.
- **Сплющивание dist в Release**: Перед расчетом контрольных сумм `dist/` очищается от вложенных папок во избежание ошибки `sha256sum: directory`.

## TDD

Тест пишется и запускается первым; отметить, что он упал по ожидаемой причине (нет класса/функции), и только потом реализация. Отчёт о проверке — с реальным выводом команды, не «должно пройти».
