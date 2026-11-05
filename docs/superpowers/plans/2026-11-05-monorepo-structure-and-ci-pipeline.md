# Реорганизация монорепозитория, гигиена зависимостей и селективный CI/Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реорганизовать плоскую структуру из 11 модулей Mimir в двухуровневую иерархию `libs/` и `apps/`, заменить `@changesets/cli` и 11 фиктивных `package.json` на Release Please, внедрить селективный CI через `dorny/paths-filter` и подключить анализатор зависимостей `com.autonomousapps.dependency-analysis`.

**Architecture:** Модули делятся строго по ролям: переиспользуемые KMP-библиотеки переезжают в `libs/` (`:libs:core`, `:libs:drive`, `:libs:client`, `:libs:runtime`, `:libs:ui`), а конечные точки входа и дистрибутивы — в `apps/` (`:apps:desktop`, `:apps:android`, `:apps:daemon`, `:apps:web-ui`, `:apps:obsidian-plugin`, `:apps:browser-extension`). Управление версией переводится на `google-github-actions/release-please-action@v4` с прямым обновлением `mimir.version` в `gradle.properties` и автоматическим вызовом `release.yml` по `workflow_call`. Матрица CI динамически сужается до `["ubuntu-latest"]`, если PR не затрагивает библиотеки, демон или десктопное приложение.

**Tech Stack:** Gradle 9.8.2 (Kotlin DSL, Composite Build), Kotlin Multiplatform 2.4.21, AGP 9.5.0, `com.autonomousapps.dependency-analysis` 3.20.1, GitHub Actions (`dorny/paths-filter@v3`, `google-github-actions/release-please-action@v4`), Node.js, `mise`.

**Spec:** `docs/superpowers/specs/2026-11-04-monorepo-structure-and-ci-pipeline-design.md`

---

## Инварианты Mimir

1. **Платформы:**
   * **Linux / Windows / macOS Desktop (`:apps:desktop`):** сохраняются задачи `:apps:desktop:run`, `:apps:desktop:packageMsi`, `:apps:desktop:packageUberJarForCurrentOS`, патч `win.norestart=true`, переменная `_JAVA_AWT_WM_NONREPARENTING=1` и сборка Arch-пакета в `.github/scripts/build-arch.sh`.
   * **Android (`:apps:android`):** сохраняется условное подключение через `build-logic/src/main/kotlin/mimir.settings.settings.gradle.kts` (`AndroidSdk.isAvailable(rootDir)`). Без Android SDK сборка проходит с предупреждением `Android SDK not found: skipping Android targets`.
   * **Daemon & Docker (`:apps:daemon`):** сохраняется встраивание бандла `:apps:web-ui` в `static/` и сборка контейнера в `packaging/docker/Dockerfile` из `/src/apps/daemon/build/install/daemon/lib`.
   * **JS/Wasm клиенты (`:apps:web-ui`, `:apps:obsidian-plugin`, `:apps:browser-extension`):** имена выходных артефактов (`web-ui.js`, `main.js`, `popup.js`, zip-архивы) не меняются, так как листовые имена подпроектов Gradle (`project.name`) сохраняются.
2. **Дизайн:**
   * Паттерн: *Separation of Concerns* + *Gradle Convention Plugins* (`build-logic`).
   * Направление зависимостей: `:apps:*` $\to$ `:libs:ui` $\to$ `:libs:runtime` $\to$ `:libs:client` / `:libs:drive` $\to$ `:libs:core`.
   * Эталонные модули: `libs/core/build.gradle.kts` (`id("mimir.kmp-library")`), `apps/desktop/build.gradle.kts`.
3. **IO и производительность сборки:**
   * Локальный и конфигурационный кэши Gradle (`org.gradle.caching=true`, `org.gradle.configuration-cache=true`) сохраняются; удалённый кэш не используется.
   * В CI `dorny/paths-filter@v3` отсекает запуск `windows-latest` и `macos-latest` при изменениях только в Android, JS/Wasm-клиентах или дизайн-токенах.
4. **UX и DX:**
   * Единый номер версии `mimir.version` в `gradle.properties` для всех клиентов; тег релиза строго `v<version>` (`"include-component-in-tag": false`), совместимый с `GitHubReleasesChannel`.
   * Удаление 22 шумовых файлов (`package.json` и `CHANGELOG.md` в 11 модулях) и полная работоспособность всех коротких команд `mise run <target>`.

---

## Global Constraints

- Единственный источник идентификаторов и версии (`mimir.version=0.2.0`) — `gradle.properties`.
- На хосте все терминальные команды выполняются строго через префикс `rtk`; использование GNU `grep` и `find` запрещено (использовать `rtk rg` и `rtk fd`).
- Сборка и тесты обязаны проходить без Android SDK (`Android SDK not found: skipping Android targets`) и без секретов (`mise.local.toml`).
- Зависимости объявляются только в `gradle/libs.versions.toml`.

## Review Focus

1. **Потеря игнорирования `build/` в `.gitignore` после углубления структуры:** правило `/*/build/` в `.gitignore` не матчит `libs/*/build/` и `apps/*/build/`, что приведёт к сотням неотслеживаемых файлов после первой же сборки. $\to$ проверить `rtk git status --short` после сборки в Task 1.
2. **Слом относительных путей `../` и `../..` из-за увеличения глубины вложенности с 1 до 2 уровней:** тесты скриншотов в `libs/ui` (`../build/reports/screenshots/desktop`) и скрипт `apps/browser-extension/scripts/capture-screenshots.mjs` (`resolve(__dirname, "../..")`) начнут писать/искать файлы в `libs/build` и `apps/` вместо корня репозитория. $\to$ проверить `mise run screenshots` и `node scripts/generate-tokens.mjs --check` в Task 2.
3. **Двойное подключение или пропуск `:apps:android`:** если оставить `"app-android"` в `mimir.settings.settings.gradle.kts` или продублировать блок `if (AndroidSdk.isAvailable)` в `settings.gradle.kts`, Android-таргет либо не подключится при наличии SDK, либо выдаст двойное предупреждение без SDK. $\to$ проверить сборку с SDK и без `local.properties` в Task 1.
4. **Несовпадение формата тега Release Please (`mimir-v0.3.0` вместо `v0.3.0`) и невызов `release.yml`:** без `"include-component-in-tag": false` в `.github/release-please-config.json` шаг `test "v$version" = "$TAG"` в `release.yml` упадёт, а без `workflow_call` из `release-please.yml` тег от `GITHUB_TOKEN` не запустит сборку артефактов. $\to$ проверить конфигурацию JSON и YAML в Task 4.
5. **Пропуск Windows/macOS при правках в `apps/daemon/**`:** так как `:apps:desktop` зависит от `:apps:daemon`, правка в демоне может сломать сборку десктопа на Windows/macOS, если `apps/daemon/**` не включён в фильтр `desktop` в `ci.yml`. $\to$ проверить фильтры `dorny/paths-filter` в Task 5.

---

### Task 1: Перемещение модулей в `libs/` и `apps/`, обновление Gradle-проектов и `.gitignore`

**Files:**
- Modify: `.gitignore`
- Modify: `settings.gradle.kts`
- Modify: `build-logic/src/main/kotlin/mimir.settings.settings.gradle.kts`
- Move & Modify:
  - `core` $\to$ `libs/core/build.gradle.kts`
  - `drive` $\to$ `libs/drive/build.gradle.kts`
  - `client` $\to$ `libs/client/build.gradle.kts`
  - `runtime` $\to$ `libs/runtime/build.gradle.kts`
  - `ui` $\to$ `libs/ui/build.gradle.kts`
  - `app-desktop` $\to$ `apps/desktop/build.gradle.kts`
  - `app-android` $\to$ `apps/android/build.gradle.kts`
  - `daemon` $\to$ `apps/daemon/build.gradle.kts`
  - `web-ui` $\to$ `apps/web-ui/build.gradle.kts`
  - `obsidian-plugin` $\to$ `apps/obsidian-plugin/build.gradle.kts`
  - `browser-extension` $\to$ `apps/browser-extension/build.gradle.kts`

**Interfaces:**
- Produces: Gradle project paths `:libs:core`, `:libs:drive`, `:libs:client`, `:libs:runtime`, `:libs:ui`, `:apps:desktop`, `:apps:android`, `:apps:daemon`, `:apps:web-ui`, `:apps:obsidian-plugin`, `:apps:browser-extension`.

- [ ] **Step 1: Переместить каталоги модулей через `git mv` в `libs/` и `apps/`**

```bash
rtk mkdir -p libs apps
rtk git mv core libs/core
rtk git mv drive libs/drive
rtk git mv client libs/client
rtk git mv runtime libs/runtime
rtk git mv ui libs/ui
rtk git mv app-desktop apps/desktop
rtk git mv app-android apps/android
rtk git mv daemon apps/daemon
rtk git mv web-ui apps/web-ui
rtk git mv obsidian-plugin apps/obsidian-plugin
rtk git mv browser-extension apps/browser-extension
```

- [ ] **Step 2: Обновить `.gitignore` для игнорирования `build/` внутри `libs/*/` и `apps/*/`**

В `.gitignore` заменить `/*/build/` на:
```gitignore
/*/build/
/libs/*/build/
/apps/*/build/
```

- [ ] **Step 3: Обновить `settings.gradle.kts` и `build-logic/src/main/kotlin/mimir.settings.settings.gradle.kts`**

В `settings.gradle.kts` заменить `include(...)` на подключение `:libs:core`, `:libs:drive`, `:libs:client`, `:libs:runtime`, `:libs:ui`, `:apps:desktop`, `:apps:daemon`, `:apps:web-ui`, `:apps:obsidian-plugin`, `:apps:browser-extension` (согласно Разделу 2.3 спеки).
В `build-logic/src/main/kotlin/mimir.settings.settings.gradle.kts` заменить:
```kotlin
if (AndroidSdk.isAvailable(rootDir)) {
    if (File(rootDir, "apps/android").isDirectory) include(":apps:android")
} else {
    Logging.getLogger("mimir").warn("Android SDK not found: skipping Android targets")
}
```

- [ ] **Step 4: Обновить межмодульные ссылки `project(...)` и `evaluationDependsOn(...)` во всех `build.gradle.kts`**

Заменить ссылки в `build.gradle.kts` модулей:
- `libs/drive/build.gradle.kts`: `api(project(":libs:core"))`
- `libs/client/build.gradle.kts`: `api(project(":libs:core"))`
- `libs/runtime/build.gradle.kts`: `api(project(":libs:core"))`, `api(project(":libs:drive"))`, `api(project(":libs:client"))`
- `libs/ui/build.gradle.kts`: `api(project(":libs:core"))`, `api(project(":libs:client"))`, `implementation(project(":libs:drive"))`
- `apps/desktop/build.gradle.kts`: `implementation(project(":libs:core"))`, `implementation(project(":libs:drive"))`, `implementation(project(":libs:client"))`, `implementation(project(":apps:daemon"))`, `implementation(project(":libs:ui"))`
- `apps/android/build.gradle.kts`: `implementation(project(":libs:core"))`, `implementation(project(":libs:drive"))`, `implementation(project(":libs:runtime"))`, `implementation(project(":libs:ui"))`
- `apps/daemon/build.gradle.kts`: `implementation(project(":libs:core"))`, `implementation(project(":libs:drive"))`, `api(project(":libs:runtime"))`, `api(project(":libs:client"))`, `evaluationDependsOn(":apps:web-ui")`, `from(project(":apps:web-ui").tasks.named("wasmJsBrowserDistribution")) { into("static") }`
- `apps/web-ui/build.gradle.kts`: `implementation(project(":libs:ui"))`, `implementation(project(":libs:core"))`
- `apps/obsidian-plugin/build.gradle.kts`: `implementation(project(":libs:core"))`
- `apps/browser-extension/build.gradle.kts`: `implementation(project(":libs:core"))`, `implementation(project(":libs:drive"))`

- [ ] **Step 5: Проверить сборку без Android SDK и со всеми модулями, а также чистоту `.gitignore`**

Run:
```bash
rtk ./gradlew -p build-logic test
rtk ./gradlew projects --console=plain
rtk ./gradlew :libs:core:jvmTest :apps:daemon:test :apps:desktop:test :apps:obsidian-plugin:checkPlugin :apps:browser-extension:checkExtension
rtk git status --short
```
Expected: `BUILD SUCCESSFUL`, в `git status --short` отсутствуют неотслеживаемые папки `libs/*/build/` и `apps/*/build/`.

- [ ] **Step 6: Commit**

```bash
rtk git add -A
rtk git commit -m "refactor(build): reorganize modules into libs/ and apps/ directories"
```

---

### Task 2: Обновление путей в скриптах токенов, скриншотов, векторов, упаковки и `mise.toml`

**Files:**
- Modify: `scripts/generate-tokens.mjs`
- Modify: `scripts/gen-rclone-vectors.sh`
- Modify: `apps/browser-extension/scripts/capture-screenshots.mjs`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/UpdateCardScreenshotTest.kt`
- Modify: `.github/scripts/build-arch.sh`
- Modify: `packaging/docker/Dockerfile`
- Modify: `mise.toml`

**Interfaces:**
- Consumes: Gradle project paths `:libs:*` и `:apps:*` из Task 1.
- Produces: Рабочие задачи `mise run <target>`, `node scripts/generate-tokens.mjs --check`, `mise run screenshots`.

- [ ] **Step 1: Запустить проверку токенов и убедиться, что она падает на старых путях**

Run: `rtk node scripts/generate-tokens.mjs --check`
Expected: FAIL (`Missing generated file: .../ui/src/commonMain/kotlin/dev/mimir/ui/MimirTokens.kt`)

- [ ] **Step 2: Обновить пути в `scripts/generate-tokens.mjs` и `scripts/gen-rclone-vectors.sh`**

В `scripts/generate-tokens.mjs`:
```javascript
const kotlinOutFile = resolve(rootDir, 'libs/ui/src/commonMain/kotlin/dev/mimir/ui/MimirTokens.kt');
const cssOutFile = resolve(rootDir, 'apps/browser-extension/src/jsMain/resources/tokens.css');
```
В `scripts/gen-rclone-vectors.sh`:
```bash
OUT="$(cd "$(dirname "$0")/.." && pwd)/libs/core/src/commonTest/kotlin/dev/mimir/core/RcloneVectors.kt"
```

- [ ] **Step 3: Обновить пути в тестах и скрипте скриншотов (`libs/ui` и `apps/browser-extension`)**

В `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/screenshots/UiScreenshotTest.kt`:
```kotlin
private val outputDir = File("../../build/reports/screenshots/desktop").apply { mkdirs() }
```
В `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/UpdateCardScreenshotTest.kt`:
```kotlin
val dir = File(System.getProperty("user.dir")).resolve("../../build/reports/screenshots/desktop").canonicalFile.apply { mkdirs() }
```
В `apps/browser-extension/scripts/capture-screenshots.mjs`:
```javascript
const rootDir = resolve(__dirname, "../../..");
const extensionHtml = resolve(rootDir, "apps/browser-extension/build/extension/popup.html");
const outputDir = resolve(rootDir, "build/reports/screenshots/extension");

if (!existsSync(extensionHtml)) {
  console.error(`Extension popup.html not found at ${extensionHtml}. Run './gradlew :apps:browser-extension:assembleExtension' first.`);
  process.exit(1);
}
```

- [ ] **Step 4: Обновить `.github/scripts/build-arch.sh`, `packaging/docker/Dockerfile` и `mise.toml`**

В `.github/scripts/build-arch.sh`:
```bash
./gradlew :apps:desktop:packageUberJarForCurrentOS --no-daemon --console=plain

jar="$(ls apps/desktop/build/compose/jars/*.jar | head -n 1)"
```
В `packaging/docker/Dockerfile`:
```dockerfile
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:daemon:installDist --no-daemon --console=plain
```
и
```dockerfile
COPY --from=build /src/apps/daemon/build/install/daemon/lib /app/lib
```
В `mise.toml` обновить все таски (`desktop`, `android`, `daemon`, `web`, `plugin`, `extension`, `build:extension`, `screenshots`, `package:extension`, `package:msi`, `package:arch`) на `:apps:*`, `:libs:ui:desktopTest` и `node apps/browser-extension/scripts/capture-screenshots.mjs`.

- [ ] **Step 5: Проверить генератор токенов и генерацию скриншотов через `mise`**

Run:
```bash
rtk node scripts/generate-tokens.mjs --check
rtk mise run plugin
rtk mise run extension
rtk mise run screenshots
```
Expected: `Design tokens are in sync.`, `BUILD SUCCESSFUL`, скриншоты сохранены в `build/reports/screenshots/desktop` и `build/reports/screenshots/extension` (не в `libs/build/`).

- [ ] **Step 6: Commit**

```bash
rtk git add scripts/ apps/browser-extension/scripts/ libs/ui/src/desktopTest/ .github/scripts/build-arch.sh packaging/docker/Dockerfile mise.toml
rtk git commit -m "build: update module paths in scripts, packaging and mise tasks"
```

---

### Task 3: Подключение анализатора зависимостей `com.autonomousapps.dependency-analysis`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: Задача `./gradlew buildHealth` и отчёт `build/reports/dependency-analysis/build-health-report.txt`.

- [ ] **Step 1: Добавить `dependency-analysis` `3.20.1` в `gradle/libs.versions.toml` и корневой `build.gradle.kts`**

В `gradle/libs.versions.toml`:
```toml
[versions]
dependencyAnalysis = "3.20.1"
```
и в `[plugins]`:
```toml
dependency-analysis = { id = "com.autonomousapps.dependency-analysis", version.ref = "dependencyAnalysis" }
```
В корневой `build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.buildconfig) apply false
}

dependencyAnalysis {
    structure {
        ignoreKtx(true)
    }
    issues {
        all {
            onAny {
                severity("warn")
            }
        }
    }
}
```

- [ ] **Step 2: Проверить выполнение `./gradlew buildHealth`**

Run: `rtk ./gradlew buildHealth --console=plain`
Expected: `BUILD SUCCESSFUL`, создан файл `build/reports/dependency-analysis/build-health-report.txt`.

- [ ] **Step 3: Commit**

```bash
rtk git add gradle/libs.versions.toml build.gradle.kts
rtk git commit -m "build(deps): integrate com.autonomousapps.dependency-analysis plugin"
```

---

### Task 4: Замена `@changesets/cli` на Release Please и обновление `release.yml`

**Files:**
- Delete: `.changeset/` (весь каталог), `pnpm-workspace.yaml`, `scripts/sync-version.mjs`, `scripts/tag-release.mjs`, `.github/workflows/changesets.yml`
- Delete: `libs/*/package.json`, `libs/*/CHANGELOG.md`, `apps/*/package.json`, `apps/*/CHANGELOG.md` (22 файла)
- Modify: `package.json`
- Create: `.github/release-please-config.json`
- Create: `.github/.release-please-manifest.json`
- Create: `.github/workflows/release-please.yml`
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Produces: `.github/release-please-config.json`, `.github/.release-please-manifest.json`, `.github/workflows/release-please.yml`, обновлённый `.github/workflows/release.yml`.

- [ ] **Step 1: Удалить артефакты `@changesets/cli`, фиктивные `package.json` и `CHANGELOG.md` из модулей**

```bash
rtk git rm -rf .changeset pnpm-workspace.yaml scripts/sync-version.mjs scripts/tag-release.mjs .github/workflows/changesets.yml
rtk git rm libs/*/package.json libs/*/CHANGELOG.md apps/*/package.json apps/*/CHANGELOG.md
```

- [ ] **Step 2: Очистить корневой `package.json`**

Записать в `package.json`:
```json
{
  "name": "mimir-monorepo",
  "private": true,
  "scripts": {
    "tokens:generate": "node scripts/generate-tokens.mjs",
    "tokens:check": "node scripts/generate-tokens.mjs --check"
  }
}
```

- [ ] **Step 3: Создать `.github/release-please-config.json`, `.github/.release-please-manifest.json` и `.github/workflows/release-please.yml`**

Создать `.github/release-please-config.json` (согласно Разделу 3.2 спеки, включая `"include-component-in-tag": false` и `extra-files` для `gradle.properties`).
Создать `.github/.release-please-manifest.json` со значением `{ ".": "0.2.0" }`.
Создать `.github/workflows/release-please.yml` (согласно Разделу 3.3 спеки, включая джобу `release`, вызывающую `./.github/workflows/release.yml` с `tag: ${{ needs.release-please.outputs.tag_name }}`).

- [ ] **Step 4: Обновить пути модулей и `generate_release_notes: false` в `.github/workflows/release.yml`**

В `.github/workflows/release.yml`:
- `msi`: `task: :apps:desktop:packageMsi`, `paths: apps/desktop/build/compose/binaries/main/msi/*.msi`
- `apk`: `task: :apps:android:assembleRelease`, `paths: apps/android/build/outputs/apk/release/*.apk`
- `obsidian-plugin`: `task: :apps:obsidian-plugin:zipPlugin`, `paths:` `apps/obsidian-plugin/build/distributions/*.zip`, `apps/obsidian-plugin/build/plugin/main.js`, `apps/obsidian-plugin/build/plugin/manifest.json`
- `browser-extension`: `task: :apps:browser-extension:zipExtension`, `paths: apps/browser-extension/build/distributions/*.zip`
- В шаге `Mark unsigned APK`: `for f in apps/android/build/outputs/apk/release/*.apk; do`
- В шаге `softprops/action-gh-release@v2`: `generate_release_notes: false`

- [ ] **Step 5: Проверить валидность JSON-манифестов и отсутствие остаточных ссылок на changesets**

Run:
```bash
rtk node -e 'JSON.parse(require("fs").readFileSync(".github/release-please-config.json")); JSON.parse(require("fs").readFileSync(".github/.release-please-manifest.json")); JSON.parse(require("fs").readFileSync("package.json")); console.log("JSON valid");'
rtk node scripts/generate-tokens.mjs --check
```
Expected: `JSON valid` и `Design tokens are in sync.`

- [ ] **Step 6: Commit**

```bash
rtk git add -A
rtk git commit -m "ci(release): replace changesets with release-please and update release workflow paths"
```

---

### Task 5: Селективный CI (`dorny/paths-filter@v3`) и обновление документации (`AGENTS.md`, `README.md`, скиллы)

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `.agents/skills/mimir-add-module/SKILL.md`
- Modify: `.agents/skills/mimir-build-verify/SKILL.md`
- Modify: `.agents/skills/mimir-cross-platform/SKILL.md`
- Modify: `.agents/skills/mimir-design-patterns/SKILL.md`
- Modify: `.agents/skills/mimir-identifiers/SKILL.md`
- Modify: `.agents/skills/mimir-ui-test/SKILL.md`

**Interfaces:**
- Consumes: Структура `libs/*` и `apps/*` из Tasks 1–4.
- Produces: Селективный `.github/workflows/ci.yml` и актуальная документация репозитория.

- [ ] **Step 1: Обновить `.github/workflows/ci.yml` с `detect-changes` (`dorny/paths-filter@v3`), динамической `os_matrix` и джобой `tokens`**

В `.github/workflows/ci.yml`:
1. Добавить джобу `detect-changes` с фильтрами `core_or_libs`, `desktop`, `android`, `js_or_web` и шагом `matrix`, формирующим `os_matrix` (`["ubuntu-latest","windows-latest","macos-latest"]` при `core_or_libs == 'true' || desktop == 'true'`, иначе `["ubuntu-latest"]`) согласно Разделу 4.2 спеки.
2. В джобе `build` указать `needs: detect-changes` и `os: ${{ fromJSON(needs.detect-changes.outputs.os_matrix) }}`.
3. Заменить джобу `changesets` на джобу `tokens`:
```yaml
  tokens:
    name: design tokens sync
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v4
        with:
          persist-credentials: false
      - uses: actions/setup-node@v4
        with:
          node-version: lts/*
      - name: Verify design tokens are in sync
        run: node scripts/generate-tokens.mjs --check
```

- [ ] **Step 2: Синхронизировать пути модулей (`libs/*`, `apps/*`, `:libs:*`, `:apps:*`) в `AGENTS.md`, `README.md` и `.agents/skills/mimir-*/SKILL.md`**

Обновить таблицы модулей, «Карту кода» и примеры команд Gradle в:
- `AGENTS.md`
- `README.md`
- `.agents/skills/mimir-add-module/SKILL.md`
- `.agents/skills/mimir-build-verify/SKILL.md`
- `.agents/skills/mimir-cross-platform/SKILL.md`
- `.agents/skills/mimir-design-patterns/SKILL.md`
- `.agents/skills/mimir-identifiers/SKILL.md`
- `.agents/skills/mimir-ui-test/SKILL.md`

- [ ] **Step 3: Проверить отсутствие устаревших Gradle-команд и путей в рабочих файлах и прогнать полную сборку**

Run:
```bash
rtk rg ':(core|drive|client|runtime|ui|app-desktop|app-android|daemon|web-ui|obsidian-plugin|browser-extension)\b' -g '!docs/**'
rtk mise run build
```
Expected: `rtk rg` возвращает 0 совпадений; `rtk mise run build` завершается с `BUILD SUCCESSFUL`.

- [ ] **Step 4: Выполнить проверку архитектурного дрейфа `context-init` и закоммитить изменения**

Run:
```bash
rtk context-init check --compact
```
Если обнаружен дрейф базы знаний `.context-init`, выполнить `rtk context-init update . --compact`.
Затем закоммитить:
```bash
rtk git add -A
rtk git commit -m "ci: add paths-filter selective matrix and update module docs"
```
