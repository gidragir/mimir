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
| build-logic | `./gradlew -p build-logic test` |
| core / drive | `./gradlew :core:allTests`, `./gradlew :drive:allTests` (jvm, js, wasmJs; js/wasm — Karma + headless Chrome) |
| ui | `./gradlew :ui:desktopTest`, `./gradlew :ui:compileKotlinWasmJs` |
| desktop | `./gradlew :app-desktop:test`, `:app-desktop:packageUberJarForCurrentOS` |
| daemon | `./gradlew :daemon:test`, затем `mise run daemon` и `curl -s 127.0.0.1:47821/health` |
| plugin / extension | `./gradlew :obsidian-plugin:checkPlugin`, `:browser-extension:checkExtension` |

Первый запуск на новой машине: `gradlew` создаётся командой `mise x gradle@9.8 -- gradle wrapper --gradle-version <9.x ≥ 9.8>`.

## Обязательная базовая линия

1. **Без Android SDK** (`ANDROID_HOME`/`ANDROID_SDK_ROOT` не заданы, нет `sdk.dir` в `local.properties`): `mise run build` проходит, в выводе `Android SDK not found: skipping Android targets`. Обратите внимание: `local.properties` имеет приоритет над `-Pandroid.sdk.dir=`, поэтому для локальной проверки сборки без SDK необходимо временно переименовать `local.properties` (например, `mv local.properties local.properties.bak`).
2. **Без `mise.local.toml`**: сборка и тесты проходят без секретов.
3. **Смена переменных в `mise.local.toml`**: если изменялись `MIMIR_*` (OAuth-клиенты, пути, порты), а приложение запускается через `:run`, убедиться, что Gradle Daemon не держит старое окружение (`./gradlew --stop`).
4. **Все цели изменённого модуля**, а не только JVM: `allTests` для `core`/`drive`, `:ui:compileKotlinWasmJs`, Android при наличии SDK. Ветвления по ОС покрыты юнит-тестами с подставленными `osName`/`env`; Windows и macOS проверяет CI-матрица — в отчёте сказать, что руками не проверено (`mimir-cross-platform`).
5. **Изменён UI** — визуальная проверка по `mimir-ui-ux` (скриншоты обеих тем и минимального размера окна).
6. Падение любого из пунктов — дефект, а не «особенность окружения».

## TDD

Тест пишется и запускается первым; отметить, что он упал по ожидаемой причине (нет класса/функции), и только потом реализация. Отчёт о проверке — с реальным выводом команды, не «должно пройти».
