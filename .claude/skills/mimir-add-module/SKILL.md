---
name: mimir-add-module
description: Use when adding a new Gradle module to the Mimir monorepo (library, launcher, JS client) — gives the checklist of settings, convention plugin, dependencies, BuildConfig and package naming.
---

# Новый модуль Mimir

1. **Слой, каталог и пакет**:
   - Переиспользуемая библиотека → `libs/<module>/src/...`, Gradle-путь `:libs:<module>`.
   - Точка входа / клиентское приложение → `apps/<module>/src/...`, Gradle-путь `:apps:<module>`.
   - Пакет `dev.mimir.<module>` (с дефисами → без дефисов, как `apps/desktop` → `dev.mimir.desktop`, `apps/obsidian-plugin` → `dev.mimir.obsidian`, `apps/web-ui` → `dev.mimir.webui`).
2. **settings.gradle.kts**: добавить `":libs:<module>"` или `":apps:<module>"` в соответствующий блок `include(...)`. `:apps:android` не добавлять вручную — его подключает `mimir.settings` (`build-logic/src/main/kotlin/mimir.settings.settings.gradle.kts`) только при наличии Android SDK.
3. **build.gradle.kts**:
   - KMP-библиотека (`libs/*`, как `libs/core`/`libs/drive`/`libs/client`): `plugins { id("mimir.kmp-library") }` (jvm, js, wasmJs, android при SDK).
   - Compose-модуль, как `libs/ui`: `kotlin("multiplatform")`, `id("mimir.android-support")`, `libs.plugins.compose.*`; цели задать явно (`jvm("desktop")`, `wasmJs`).
   - Точка входа (`apps/*`: desktop/daemon/…): проект с зависимостью на `:libs:ui`/`:libs:runtime`/`:libs:client`/`:libs:core`.
4. **Зависимости** — только через `gradle/libs.versions.toml` (`libs.*`). Новые версии добавлять туда же.
5. **Идентификаторы** (имя, версия, id приложения и т. п.) не писать литералами: брать из `gradle.properties` через BuildConfig/`processResources` (см. скилл `mimir-identifiers`). `libs/core` уже генерирует `BuildConfig` в `dev.mimir.core`.
6. **Направление зависимостей**: точки входа (`:apps:*`) → `:libs:ui` / `:libs:runtime` → `:libs:client` / `:libs:drive` → `:libs:core`. `libs/core` без платформенного кода.
7. **Тесты первыми** (скилл `test-driven-development`), проверка — скилл `mimir-build-verify` + `./gradlew buildHealth` (проверка графа зависимостей). Модуль не должен ломать сборку без Android SDK и без секретов.
8. **Документация, CI и AST-индекс**:
   - Обновить таблицу модулей и «Карту кода» в `AGENTS.md`, `README.md`.
   - При добавлении нового приложения в `apps/<module>` добавить соответствующий фильтр в `.github/workflows/ci.yml` (`detect-changes`).
   - Обновить базу знаний: `rtk context-init init . --force --with-rtk --compact`.
9. Роль модуля в архитектуре — по скиллу `mimir-design-patterns` (порт/адаптер, точка входа, composition root); платформы, на которых он собирается и работает, — по `mimir-cross-platform`.
