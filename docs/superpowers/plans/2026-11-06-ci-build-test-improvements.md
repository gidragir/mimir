# Комплексная стабилизация и оптимизация CI/Release, сборки Gradle и тестирования — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Устранить гонку `release-please.yml` / `ci.yml` при выпуске релизов по всем платформам, ввести каноническое именование Android APK (`mimir-<version>.apk` / `mimir-<version>-unsigned.apk`), починить `./gradlew buildHealth`, убрать холостую компиляцию Wasm/Android в Gradle и унифицировать контур тестирования (Karma, скриншоты, клиенты).

**Architecture:** В `release.yml` выделяется легковесная gate-джоба `verify`, ожидающая завершения активных `check-runs` коммита перед стартом `build`/`arch`/`docker`, а в `ci.yml` на релизных коммитах `chore(main): release *` отключается дублирующая сборка MSI. Настройки JVM 26 и `dependency-analysis` переносятся в `build-logic` на единый classloader с KGP/AGP; сборка APK оформляется задачей `packageReleaseApk` в `:apps:android`; настройки Karma (`ChromeHeadlessCI` + таймаут Mocha 120 с) централизуются в `gradle/karma.config.d/`.

**Tech Stack:** Gradle 9.8+, Kotlin Multiplatform 2.4.21 (JVM 26, JS, WasmJs), AGP 9.5.0 (compileSdk 37), AutonomousApps Dependency Analysis 3.20.1, GitHub Actions, Karma + Headless Chromium.

**Spec:** `docs/superpowers/specs/2026-11-06-ci-build-test-improvements-design.md`

## Global Constraints

- Все идентификаторы (`mimir.appName`, `mimir.version`, `mimir.pacmanName`, `mimir.obsidianPluginId`) читаются исключительно из `gradle.properties` через `providers.gradleProperty(...)`.
- Сборка без Android SDK (`env -u ANDROID_HOME -u ANDROID_SDK_ROOT` и без `local.properties`) обязана проходить с предупреждением `Android SDK not found: skipping Android targets`.
- Все терминальные команды выполняются строго с префиксом `rtk`; поиск файлов и кода — строго через `rtk fd` и `rtk rg`.
- Configuration Cache (`org.gradle.configuration-cache=true`) и Build Cache (`org.gradle.caching=true`) не должны ломаться ни одной задачей.

## Инварианты Mimir

1. **Платформы:**
   - **Linux (`ubuntu-latest` / Arch):** `ciJvmTests` и `build --continue` выполняются с единым графом проектов без переключения `-Pmimir.bundleWebUi`, сохраняя `UP-TO-DATE` статус `:apps:daemon:test` и `:apps:desktop:test`; ветки `is_release_pr` и `js_or_web`-only запускаются с `env -u ANDROID_HOME -u ANDROID_SDK_ROOT`.
   - **Windows (`windows-latest`):** включается в `os_matrix` при `workflow_dispatch`; на коммитах `chore(main): release *` в `ci.yml` пропускает `packageMsi` (`package_msi=false`), выполняя только `ciJvmTests`.
   - **macOS (`macos-latest`):** запускается по `schedule` и `workflow_dispatch` (`run_macos=true`).
   - **Android (`:apps:android`):** задача `packageReleaseApk` формирует `build/distributions/mimir-<version>.apk` (с ключом подписи) или `mimir-<version>-unsigned.apk` (без ключа); отключены дублирующие `testReleaseUnitTest` и `lintVitalRelease`.
   - **Docker и JS-клиенты:** `Dockerfile` и `build-arch.sh` явно передают `-Pmimir.bundleWebUi=true`; все JS-модули используют единый `gradle/karma.config.d/`.
2. **Дизайн:**
   - Convention-плагин `mimir.jvm` в `build-logic` устраняет дублирование настроек JVM 26 между `:apps:desktop` и `:apps:daemon`.
   - Единый classloader в `build-logic` связывает `com.autonomousapps.dependency-analysis` с `kotlin-gradle-plugin` и `agp`.
3. **IO:**
   - Исключена холостая сборка 12 MiB Wasm-бандла и `wasm-opt` в библиотеке `:libs:ui`, исключена запись PNG-скриншотов при обычном прогоне `ciJvmTests`.
4. **UX:**
   - Релизный Android-ассет в GitHub Releases получает имя `mimir-<version>.apk` (или `mimir-<version>-unsigned.apk`), распознаваемое `GitHubReleasesChannel`.

## Review Focus

- Гонка при старте `release.yml`: когда `Detect Changed Modules` в `ci.yml` ещё выполняется (`total == 0` для `build (`), цикл в `verify` должен ждать появления и завершения `build (` чек-ранов, но немедленно выходить, если сам `Detect Changed Modules` завершился с ошибкой (`detect_status == "completed" && detect != "success"`).
- Имя APK при отсутствии секретов подписи: `packageReleaseApk` обязан выдавать суффикс `-unsigned.apk` (`mimir-<version>-unsigned.apk`), чтобы `GitHubReleasesChannel.classify` не предлагал неподписанный APK для автообновления.
- Работа `WebUiServedTest` без полной сборки Wasm: `processTestResources` в `:apps:daemon` должен подкладывать `apps/web-ui/src/wasmJsMain/resources/index.html` в `static/index.html`, чтобы тест проходил в `ciJvmTests`.
- Изоляция скриншотных тестов: запуск `./gradlew :libs:ui:desktopTest` в составе `ciJvmTests` не должен выполнять `*ScreenshotTest`, но `mise run screenshots` (с `-Pmimir.screenshots=true`) обязан их выполнить.
- Код возврата `capture-screenshots.mjs`: при падении Chromium на любом таргете скрипт обязан завершиться с `process.exit(1)`.

---

### Task 1: Convention-плагин `mimir.jvm`, починка `buildHealth` и оптимизация `:libs:ui` / `:apps:daemon`

**Files:**
- Modify: `gradle.properties`
- Modify: `gradle/libs.versions.toml`
- Modify: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/mimir.jvm.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `libs/ui/build.gradle.kts`
- Delete: `libs/ui/webpack.config.d/performance.js`
- Modify: `apps/daemon/build.gradle.kts`
- Modify: `apps/desktop/build.gradle.kts`

**Interfaces:**
- Produces: convention plugin `id("mimir.jvm")`, working `./gradlew buildHealth` (`projectCount > 0`), always-enabled `WebUiServedTest` in `:apps:daemon:test`.

- [ ] **Step 1: Добавить `org.gradle.parallel=true` в `gradle.properties` и библиотеку `dependency-analysis-plugin` в `gradle/libs.versions.toml`**

В `gradle.properties` после `org.gradle.caching=true` добавить:
```properties
org.gradle.parallel=true
```
В `gradle/libs.versions.toml` в секцию `[libraries]` добавить:
```toml
dependency-analysis-plugin = { module = "com.autonomousapps:dependency-analysis-gradle-plugin", version.ref = "dependencyAnalysis" }
```

- [ ] **Step 2: Подключить `dependency-analysis-plugin` в `build-logic/build.gradle.kts` и создать `build-logic/src/main/kotlin/mimir.jvm.gradle.kts`**

В `build-logic/build.gradle.kts` в `dependencies` добавить `implementation(libs.dependency.analysis.plugin)`.
Создать `build-logic/src/main/kotlin/mimir.jvm.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    org.jetbrains.kotlin.jvm
}

group = providers.gradleProperty("mimir.group").get()
version = providers.gradleProperty("mimir.version").get()

tasks.withType<JavaCompile>().configureEach {
    options.release = 26
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_26)
}
```

- [ ] **Step 3: Применить `com.autonomousapps.dependency-analysis` ко всем подпроектам в корневом `build.gradle.kts`**

В `build.gradle.kts` заменить `alias(libs.plugins.dependency.analysis)` на `id("com.autonomousapps.dependency-analysis")` и добавить:
```kotlin
subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")
}
```

- [ ] **Step 4: Перевести `apps/daemon/build.gradle.kts` и `apps/desktop/build.gradle.kts` на `id("mimir.jvm")` и подключить тестовый `index.html` в `:apps:daemon`**

В `apps/desktop/build.gradle.kts` и `apps/daemon/build.gradle.kts`:
- Заменить `kotlin("jvm")` на `id("mimir.jvm")`, удалить дублирующиеся `group = ...`, `version = ...` и блоки `tasks.withType<JavaCompile>` / `tasks.withType<KotlinCompile>`.
- В `apps/daemon/build.gradle.kts` удалить ветку `else { tasks.test { filter { excludeTestsMatching("*WebUiServedTest") } } }` и добавить:
```kotlin
tasks.processTestResources {
    from(rootProject.file("apps/web-ui/src/wasmJsMain/resources")) {
        include("index.html")
        into("static")
    }
}
```

- [ ] **Step 5: Удалить `binaries.executable()` из `libs/ui/build.gradle.kts` и удалить `libs/ui/webpack.config.d/performance.js`**

В `libs/ui/build.gradle.kts` в блоке `wasmJs` оставить только `browser()`, удалив строку `binaries.executable()`. Удалить файл `libs/ui/webpack.config.d/performance.js`.

- [ ] **Step 6: Проверить `buildHealth`, `WebUiServedTest` и отсутствие `wasmJsBrowserProductionWebpack` в `:libs:ui`**

Run: `rtk ./gradlew buildHealth :apps:daemon:test --tests '*WebUiServedTest' :libs:ui:assemble`
Expected: `BUILD SUCCESSFUL`, в `build/reports/dependency-analysis/build-health-report.json` поле `"projectCount"` > 0, задача `:libs:ui:wasmJsBrowserProductionWebpack` не существует/не вызывается.

---

### Task 2: Каноническое именование Android APK (`packageReleaseApk`) и оптимизация `:apps:android`

**Files:**
- Modify: `apps/android/build.gradle.kts`
- Modify: `apps/android/src/test/kotlin/dev/mimir/android/ManifestPermissionsTest.kt`
- Modify: `.agents/skills/mimir-build-verify/SKILL.md`
- Modify: `.claude/skills/mimir-build-verify/SKILL.md`

**Interfaces:**
- Produces: Gradle-задача `:apps:android:packageReleaseApk`, создающая `apps/android/build/distributions/mimir-<version>.apk` (при заданном `MIMIR_KEYSTORE_PATH`) или `apps/android/build/distributions/mimir-<version>-unsigned.apk` (без ключа).

- [ ] **Step 1: Настроить `base.archivesName`, задачу `packageReleaseApk`, `lint.checkReleaseBuilds = false` и отключение `ReleaseUnitTest` в `apps/android/build.gradle.kts`**

В `apps/android/build.gradle.kts`:
1. Добавить:
```kotlin
val pacmanName = providers.gradleProperty("mimir.pacmanName").get()

base {
    archivesName.set("$pacmanName-$appVersion")
}
```
2. Внутри блока `android { ... }` добавить:
```kotlin
    lint {
        checkReleaseBuilds = false
    }
```
3. После блока `android { ... }` добавить:
```kotlin
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as? com.android.build.api.variant.HasUnitTestBuilder)?.enableUnitTest = false
    }
}

val releaseApkFileName = if (keystorePath != null) {
    "$pacmanName-$appVersion.apk"
} else {
    "$pacmanName-$appVersion-unsigned.apk"
}

tasks.register<Sync>("packageReleaseApk") {
    group = "build"
    description = "Собрать релизный APK и скопировать в build/distributions с каноническим именем"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { releaseApkFileName }
    }
    into(layout.buildDirectory.dir("distributions"))
}
```
4. В `.agents/skills/mimir-build-verify/SKILL.md` и `.claude/skills/mimir-build-verify/SKILL.md` обновить путь debug APK на `apps/android/build/outputs/apk/debug/mimir-*-debug.apk`.

- [ ] **Step 2: Проверить сборку `packageReleaseApk` и отсутствие `testReleaseUnitTest`**

Run: `rtk ./gradlew :apps:android:packageReleaseApk :apps:android:test`
Expected: `BUILD SUCCESSFUL`, файл `apps/android/build/distributions/mimir-0.2.0-unsigned.apk` создан, `testReleaseUnitTest` не запускается.

---

### Task 3: Централизация Karma (`gradle/karma.config.d/`), изоляция скриншотов и unit-тесты клиентов

**Files:**
- Create: `gradle/karma.config.d/headless.js`
- Create: `gradle/karma.config.d/mocha-timeout.js`
- Delete: `apps/browser-extension/karma.config.d/headless.js`
- Delete: `apps/browser-extension/karma.config.d/mocha-timeout.js`
- Delete: `libs/core/karma.config.d/mocha-timeout.js`
- Modify: `build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts`
- Modify: `apps/browser-extension/build.gradle.kts`
- Modify: `apps/obsidian-plugin/build.gradle.kts`
- Create: `apps/obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/ObsidianPluginTest.kt`
- Modify: `apps/web-ui/build.gradle.kts`
- Create: `apps/web-ui/src/wasmJsTest/kotlin/dev/mimir/web/WebUiSmokeTest.kt`
- Modify: `libs/ui/build.gradle.kts`
- Modify: `mise.toml`
- Modify: `apps/browser-extension/scripts/capture-screenshots.mjs`

**Interfaces:**
- Produces: общий каталог `gradle/karma.config.d/`, флаг `-Pmimir.screenshots=true` для скриншотных тестов `:libs:ui:desktopTest`, unit-тесты в `:apps:obsidian-plugin:jsBrowserTest` и `:apps:web-ui:wasmJsNodeTest`.

- [ ] **Step 1: Перенести `headless.js` и `mocha-timeout.js` в `gradle/karma.config.d/` и подключить в `mimir.kmp-library.gradle.kts` и `apps/browser-extension/build.gradle.kts`**

Переместить `apps/browser-extension/karma.config.d/headless.js` и `mocha-timeout.js` в `gradle/karma.config.d/`, удалить старые папки `karma.config.d` из `apps/browser-extension` и `libs/core`.
В `build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts` и `apps/browser-extension/build.gradle.kts` в `testTask` указать:
```kotlin
useKarma {
    useChromeHeadless()
    useConfigDirectory(rootProject.file("gradle/karma.config.d"))
}
```

- [ ] **Step 2: Изолировать `*ScreenshotTest` в `libs/ui/build.gradle.kts` по флагу `-Pmimir.screenshots=true` и исправить код возврата в `capture-screenshots.mjs`**

В `libs/ui/build.gradle.kts`:
```kotlin
val runScreenshots = providers.gradleProperty("mimir.screenshots").map { it.toBoolean() }.orElse(false).get()
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (!runScreenshots) {
        filter {
            excludeTestsMatching("*ScreenshotTest")
        }
    }
}
```
В `mise.toml` обновить задачу `screenshots`:
```toml
[tasks.screenshots]
description = "Сгенерировать скриншоты UI desktop и расширения браузера"
run = "./gradlew :libs:ui:desktopTest -Pmimir.screenshots=true --rerun --tests 'dev.mimir.ui.*Screenshot*' && ./gradlew :apps:browser-extension:assembleExtension && node apps/browser-extension/scripts/capture-screenshots.mjs"
run_windows = "gradlew.bat :libs:ui:desktopTest -Pmimir.screenshots=true --rerun --tests dev.mimir.ui.*Screenshot* && gradlew.bat :apps:browser-extension:assembleExtension && node apps/browser-extension/scripts/capture-screenshots.mjs"
```
В `apps/browser-extension/scripts/capture-screenshots.mjs` добавить переменную `let failed = false;`, в `catch (err)` выставлять `failed = true;`, а после цикла `if (failed) process.exit(1);`.

- [ ] **Step 3: Добавить unit-тесты в `:apps:obsidian-plugin` и `:apps:web-ui`**

В `apps/obsidian-plugin/build.gradle.kts`:
- В `js { browser { ... } }` добавить `testTask` с `useKarma { useChromeHeadless(); useConfigDirectory(rootProject.file("gradle/karma.config.d")) }` и `jsTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }`.
- Создать `apps/obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/ObsidianPluginTest.kt`, проверяющий `SyncStatus.NotConfigured.label() == "Mimir: not connected"` и инициализацию `ObsidianInPlaceApplier`.
В `apps/web-ui/build.gradle.kts`:
- В `wasmJs` добавить `nodejs()` и `wasmJsTest.dependencies { implementation(kotlin("test")) }`.
- Создать `apps/web-ui/src/wasmJsTest/kotlin/dev/mimir/web/WebUiSmokeTest.kt`, проверяющий модель `FakeMimirUiModel` и `SyncStatus.NotConfigured.label()`.

- [ ] **Step 4: Проверить тесты JS/Wasm и скриншоты**

Run: `rtk ./gradlew :libs:client:jsBrowserTest :apps:obsidian-plugin:jsBrowserTest :apps:web-ui:wasmJsNodeTest && rtk mise run screenshots`
Expected: `BUILD SUCCESSFUL`, все тесты и генерация скриншотов проходят с кодом `0`.

---

### Task 4: Стабилизация `.github/workflows/release.yml`, `.github/workflows/ci.yml`, `build-arch.sh` и `Dockerfile`

**Files:**
- Modify: `.github/workflows/release.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/scripts/build-arch.sh`
- Modify: `packaging/docker/Dockerfile`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: `:apps:android:packageReleaseApk` из Task 2, обновлённый `:apps:daemon:test` из Task 1.
- Produces: синхронизированные workflows `ci.yml` и `release.yml`, готовые к авто-релизу из Release Please.

- [ ] **Step 1: Обновить `.github/workflows/release.yml` (добавить джобу `verify`, переключить Android на `packageReleaseApk`, добавить кэш Gradle в `arch`)**

В `.github/workflows/release.yml`:
1. Добавить джобу `verify` перед `build` с проверкой `Tag matches mimir.version` и циклом ожидания `check-runs` (до 60 попыток по 15 с, выход при `detect_status == "completed" && detect != "success"` или когда `detect == "success" && total > 0 && in_progress == 0`).
2. В `build`, `arch`, `docker` указать `needs: verify`.
3. Из `build` убрать дублирующиеся шаги `Tag matches mimir.version`, `Verify CI status for release commit` и `Mark unsigned APK`; в `linux-bundles` заменить `:apps:android:assembleRelease` на `:apps:android:packageReleaseApk` и путь `apps/android/build/outputs/apk/release/*.apk` на `apps/android/build/distributions/*.apk`.
4. В `arch` добавить `actions/cache@v5` для `/root/.gradle/caches` и `/root/.gradle/wrapper`.

- [ ] **Step 2: Обновить `.github/workflows/ci.yml`, `.github/scripts/build-arch.sh`, `packaging/docker/Dockerfile` и `AGENTS.md`**

В `.github/workflows/ci.yml`:
1. Обновить версию `actionlint` до `1.7.12`.
2. В шаге `matrix` (`detect-changes`):
   - Передать `COMMIT_SUBJECT: ${{ github.event.head_commit.message }}`.
   - При `EVENT_NAME == "workflow_dispatch"` (и `RUN_MACOS != "true"`) задать `package_msi=true` и `os_matrix=["ubuntu-latest","windows-latest"]`.
   - При `[[ "$COMMIT_SUBJECT" == "chore(main): release "* ]]` задать `package_msi=false` (сохраняя `os_matrix=["ubuntu-latest","windows-latest"]`).
3. В шаге `Full build and packaging`:
   - Учесть `github.event_name == 'workflow_dispatch'` в `CORE_OR_LIBS` и `DESKTOP`.
   - В ветках `is_release_pr == 'true'` и `js_or_web`-only запускать `./gradlew` с `env -u ANDROID_HOME -u ANDROID_SDK_ROOT`.
   - В ветке `else` вызывать `./gradlew build --continue` (без `-Pmimir.bundleWebUi=true`).
4. В `arch-dry-run` добавить `actions/cache@v5` для `/root/.gradle/caches` и `/root/.gradle/wrapper`.
5. В `.github/scripts/build-arch.sh` и `packaging/docker/Dockerfile` добавить `-Pmimir.bundleWebUi=true` к командам `./gradlew`.
6. Обновить описание CI/APK в `AGENTS.md`.

- [ ] **Step 3: Проверить синтаксис workflows через `actionlint`**

Run: `rtk actionlint`
Expected: выход с кодом `0` без предупреждений.

---

### Task 5: Полная верификация и синхронизация `context-init`

**Files:**
- Modify: `.context-init/knowledge/**` (через `context-init`)

- [ ] **Step 1: Выполнить быструю проверку без Android SDK (`ci:fast`) и полный прогон (`mise run build`)**

Run: `rtk mise run ci:fast && rtk mise run build`
Expected: `BUILD SUCCESSFUL` во всех задачах.

- [ ] **Step 2: Проверить и обновить базу знаний `context-init`**

Вызвать MCP-инструмент `context_check` (и `context_update` при наличии дрейфа).
Expected: `has_drift: false`.
