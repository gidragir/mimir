# CI/CD Optimization & Release v0.3.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Оптимизировать CI/CD пайплайны (`.github/workflows/ci.yml`, `release.yml`, `release-please.yml`), устранить гонку виртуального и реального времени в `ConflictAndDeviceAuthRoutesTest`, и выпустить релиз `v0.3.0` в GitHub Releases со всеми артефактами.

**Architecture:** Двухконтурная фильтрация изменений (`dorny/paths-filter@v3`) управляет как матрицей ОС (`ubuntu-latest`, `windows-latest`), так и набором запускаемых Gradle-задач. На Linux выполняется кроссплатформенный набор тестов (или целевой поднабор для изолированных правок `android` / `js_or_web`), на Windows — только десктопные юнит-тесты и сборка MSI (`:apps:desktop:test :apps:desktop:packageMsi`), а в `release.yml` сборка `apk`, `obsidian-plugin` и `browser-extension` объединена в один Linux-раннер.

**Tech Stack:** GitHub Actions, `dorny/paths-filter@v3`, `google-github-actions/release-please-action@v4`, Gradle 9.8.2, Kotlin Multiplatform 2.4.21.

**Spec:** `docs/superpowers/specs/2026-11-04-monorepo-structure-and-ci-pipeline-design.md`

## Инварианты Mimir

1. **Платформы:**
   - **Linux (`ubuntu-latest`):** выполняет полный `mise run build` при изменениях в `libs/**`/`desktop`, либо целевые таски при изолированных изменениях `android` / `js_or_web`, либо быструю проверку версии на PR `release-please--*`.
   - **Windows (`windows-latest`):** запускается только при изменениях в `libs/**` / `apps/desktop/**` / `apps/daemon/**` и выполняет строго `./gradlew :apps:desktop:test :apps:desktop:packageMsi`. В `release.yml` предварительно настраивает `Developer Mode`, `LongPathsEnabled` и `core.symlinks`.
   - **macOS (`macos-latest`):** исключён из блокирующей PR-матрицы; запускается по требованию через `workflow_dispatch` (`run_macos=true`).
   - **Android / Docker / JS-клиенты:** в `release.yml` APK, плагин Obsidian и расширение браузера собираются в едином Linux-прогоне Gradle без тройной перекомпиляции `:libs:core` и `:libs:drive`.
2. **Дизайн:** селективная маршрутизация задач в `.github/workflows/ci.yml`, сериализация обновлений состояния конфликтов через `Mutex` в `ConflictTracker`.
3. **IO:** исключение холостых прогонов Gradle/Karma на Windows и Release Please PR; сокращение числа параллельных Linux VM при сборке релиза с 5 до 3.
4. **UX:** неприменимо (изменения в инфраструктуре CI/CD и тестах демона).

---

### Task 1: Устранение гонки виртуального времени в `ConflictAndDeviceAuthRoutesTest` и `ConflictTracker`

**Files:**
- Modify: `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/ConflictTracker.kt`
- Modify: `apps/daemon/src/test/kotlin/dev/mimir/daemon/ConflictAndDeviceAuthRoutesTest.kt`

- [ ] **Step 1: Защитить `ConflictTracker.refresh()` мьютексом и перевести ожидание файлового наблюдателя в `ConflictAndDeviceAuthRoutesTest` на реальные часы (`Dispatchers.Default`)**

В `ConflictTracker.kt` добавить `Mutex` вокруг `refresh()`, чтобы параллельные вызовы из `onWatching` и `collect` не перезаписывали актуальный снимок старым.
В `ConflictAndDeviceAuthRoutesTest.kt` (`status counts active conflicts`) обернуть цикл ожидания `withTimeout(5_000)` в `withContext(Dispatchers.Default)`, чтобы `delay(50)` не прокручивал виртуальные часы `testApplication` (`runTest`) быстрее реального потока `NioWatchServiceVaultWatcher` на `Dispatchers.IO`.

- [ ] **Step 2: Прогнать тесты `:libs:runtime:jvmTest` и `:apps:daemon:test`**

Run: `rtk ./gradlew :libs:runtime:jvmTest :apps:daemon:test`
Expected: `BUILD SUCCESSFUL`

---

### Task 2: Оптимизация `.github/workflows/ci.yml`, `release.yml` и `release-please.yml`

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `.github/workflows/release-please.yml`
- Modify: `AGENTS.md`
- Modify: `.claude/skills/mimir-build-verify/SKILL.md`

- [ ] **Step 1: Внедрить проверку токенов и селективный выбор Gradle-задач в `.github/workflows/ci.yml`**

1. Перенести `node scripts/generate-tokens.mjs --check` внутрь `detect-changes` и удалить отдельную VM-джобу `tokens`.
2. Исключить `macos-latest` из автоматической матрицы (`workflow_dispatch` с `run_macos=true` для ручного запуска).
3. Для веток `release-please--*` (`is_release_pr=true`) оставить только `ubuntu-latest` и выполнять `./gradlew -p build-logic test && ./gradlew :apps:desktop:help`.
4. На `windows-latest` запускать `./gradlew :apps:desktop:test :apps:desktop:packageMsi`.
5. На `ubuntu-latest` пропускать `setup-android`, если `core_or_libs != 'true' && android != 'true'`, и запускать целевые таски при изолированных изменениях `android` или `js_or_web`.

- [ ] **Step 2: Объединить сборку Linux-артефактов в `.github/workflows/release.yml`**

Объединить `apk`, `obsidian-plugin` и `browser-extension` в один матричный элемент `linux-bundles` на `ubuntu-latest`, выполняющий `./gradlew :apps:android:assembleRelease :apps:obsidian-plugin:zipPlugin :apps:browser-extension:zipExtension`, и настроить реестр/симлинки на `windows-latest` перед `actions/checkout`.

- [ ] **Step 3: Проверить валидность YAML и синхронизацию токенов**

Run: `rtk node scripts/generate-tokens.mjs --check`
Expected: `Design tokens are up to date.`

---

### Task 3: Коммит, пуш и выпуск релиза `v0.3.0` через GitHub Actions

**Files:**
- Commit & Push: `main`

- [ ] **Step 1: Закоммитить все изменения и запушить в `main`**
- [ ] **Step 2: Дождаться прохождения оптимизированного `CI` и обновления PR `#3` от Release Please**
- [ ] **Step 3: Влить PR `#3` (`chore(main): release 0.3.0`), проконтролировать выполнение `Release Please` + `Release` (`v0.3.0`) и проверить опубликованные ассеты релиза `v0.3.0` через `gh release view v0.3.0`**
