---
name: mimir-add-module
description: Use when adding a new Gradle module to the Mimir monorepo (library, launcher, JS client) — gives the checklist of settings, convention plugin, dependencies, BuildConfig and package naming.
---

# Новый модуль Mimir

1. **Каталог и пакет**: `<module>/src/...`, пакет `dev.mimir.<module>` (с дефисами → точки/без дефисов, как `apps/desktop` → `dev.mimir.desktop`).
2. **settings.gradle.kts**: добавить в `include(...)`. `:apps:android` не добавлять вручную — его подключает `mimir.settings` только при наличии Android SDK.
3. **build.gradle.kts**:
   - KMP-библиотека, как `libs/core`/`libs/drive`: `plugins { id("mimir.kmp-library") }` (jvm, js, wasmJs, android при SDK).
   - Compose-модуль, как `libs/ui`: `kotlin("multiplatform")`, `id("mimir.android-support")`, `libs.plugins.compose.*`; цели задать явно (`jvm("desktop")`, `wasmJs`).
   - Точка входа (desktop/daemon/…): JVM-проект с зависимостью на `:libs:ui`/`:libs:core`.
4. **Зависимости** — только через `gradle/libs.versions.toml` (`libs.*`). Новые версии добавлять туда же.
5. **Идентификаторы** (имя, версия, id приложения и т. п.) не писать литералами: брать из `gradle.properties` через BuildConfig/`processResources` (см. скилл `mimir-identifiers`). `libs/core` уже генерирует `BuildConfig` в `dev.mimir.core`.
6. **Направление зависимостей**: точки входа → `libs/ui` → `libs/drive` → `libs/core`. `libs/core` без платформенного кода.
7. **Тесты первыми** (скилл `test-driven-development`), проверка — скилл `mimir-build-verify`. Модуль не должен ломать сборку без Android SDK и без секретов.
8. Обновить таблицу модулей и «Карту кода» в `AGENTS.md`, README.
9. Роль модуля в архитектуре — по скиллу `mimir-design-patterns` (порт/адаптер, точка входа, composition root); платформы, на которых он собирается и работает, — по `mimir-cross-platform`.
