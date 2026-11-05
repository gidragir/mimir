# Fast Local Testing & GitHub Actions Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Обеспечить быстрый локальный прогон всех JVM-тестов и линтеров без сборки Kotlin/Wasm (`mise run test:fast`, `mise run ci:fast`), устранить падение `:apps:web-ui:wasmJsBrowserProductionWebpack` на Windows (`@js-joda/core`) и перестроить GitHub Actions (`ci.yml`, `release.yml`) для минимального времени обратной связи и 100% покрытия платформенных тестов.

**Architecture:** В `apps/daemon/build.gradle.kts` упаковка `:apps:web-ui:wasmJsBrowserDistribution` в `static/` отвязывается от обычных тестовых прогонов и активируется только при передаче `-Pmimir.bundleWebUi=true` или запуске задач дистрибуции (`package*`, `*Distributable*`, `installDist`, `distTar`, `distZip`). В корневом `build.gradle.kts` регистрируется агрегатор `ciJvmTests` (все JVM/Desktop-тесты подпроектов + `:build-logic:test`), который используется как для локальной предпроверки (`mise run ci:fast`), так и первым быстрым шагом в Linux/Windows/macOS джобах `ci.yml`.

**Tech Stack:** Gradle 9.8.2 (Kotlin DSL, Configuration Cache), Kotlin Multiplatform 2.4.21 (JVM 26, JS, WasmJs), `mise`, `actionlint`, GitHub Actions (`dorny/paths-filter@v4`, `gradle/actions/setup-gradle@v6`).

**Spec:** `docs/superpowers/specs/2026-11-05-ci-and-local-testing-design.md`

## Global Constraints

- Все терминальные команды выполняются строго с префиксом `rtk`.
- Использование GNU `find` и `grep` запрещено (только `rtk fd` и `rtk rg`).
- Configuration Cache и Build Cache в Gradle обязаны оставаться рабочими.
- Базовая сборка и `ciJvmTests` обязаны проходить без Android SDK и без секретов (`mise.local.toml`).
- В релизных артефактах (`packageMsi`, `packageUberJarForCurrentOS`, `installDist` в Docker) директория `static/` с Web UI обязана присутствовать.

## Review Focus

1. **Упаковка демона в Docker и Arch без явного флага `-Pmimir.bundleWebUi=true`:** `packaging/docker/Dockerfile` вызывает `:apps:daemon:installDist`, а `.github/scripts/build-arch.sh` вызывает `:apps:desktop:packageUberJarForCurrentOS`. Автоопределение по `gradle.startParameter.taskNames` обязано включать `wasmJsBrowserDistribution` для обоих сценариев.
2. **Сборка без Android SDK:** `ciJvmTests` не должен вызывать задачи модуля `:apps:android`, чтобы не требовать Android SDK и Robolectric при быстром прогоне.
3. **Configuration Cache при переключении `-Pmimir.bundleWebUi`:** чтение `providers.gradleProperty("mimir.bundleWebUi")` и `gradle.startParameter.taskNames` на фазе конфигурации совместимо с Configuration Cache (формирует раздельные ключи кэша для тестовых и упаковочных запусков).
4. **Проверка `check-runs` в `release.yml`:** GitHub API возвращает массив `check_runs`; если коммит в `main` ещё выполняется или упал, релиз должен прерываться с ненулевым кодом возврата.
5. **Срабатывание `checkExtension` в ветке `JS_OR_WEB`:** вызов `./gradlew :apps:browser-extension:checkExtension` обязан приводить к запуску `:apps:browser-extension:jsBrowserTest`.

## Инварианты Mimir

1. **Платформы:** Linux (`ubuntu-latest` + локальный Arch), Windows (`windows-latest` — все JVM-тесты + условный `packageMsi`), macOS (`macos-latest` в `schedule` и `workflow_dispatch`), Android, Docker и Arch (dry-run в `schedule`), JS/Wasm (`checkExtension` включает `jsBrowserTest`).
2. **Дизайн:** агрегатор `ciJvmTests` и логика `bundleWebUi` живут в Gradle-скриптах (`build.gradle.kts`, `apps/daemon/build.gradle.kts`), а не в YAML.
3. **IO:** исключена холостая сборка Wasm/Binaryen/Webpack при прогоне JVM-тестов.
4. **UX:** неприменимо (интерфейс не меняется; `static/` сохраняется во всех пользовательских пакетах).

---

### Task 1: Отвязка Wasm от JVM-тестов, фиксация `@js-joda/core`, агрегатор `ciJvmTests` и локальные таски `mise.toml`

**Files:**
- Modify: `apps/daemon/build.gradle.kts:59-64`
- Modify: `apps/web-ui/build.gradle.kts:16-23`
- Modify: `apps/browser-extension/build.gradle.kts:94-100`
- Modify: `build.gradle.kts:1-20`
- Modify: `mise.toml:5-15`

**Interfaces:**
- Produces: Gradle task `:ciJvmTests`, Gradle property `mimir.bundleWebUi`, mise tasks `test:fast` and `ci:fast`.

- [ ] **Step 1: Сделать подключение `:apps:web-ui:wasmJsBrowserDistribution` в `apps/daemon/build.gradle.kts` условным**

В `apps/daemon/build.gradle.kts` заменить строки 60–64:
```kotlin
// Web UI раздаётся демоном: бандл :web-ui попадает в ресурсы static/ только при упаковке дистрибутивов
// или явном -Pmimir.bundleWebUi=true, чтобы JVM-тесты (:apps:daemon:test, :apps:desktop:test) не тянули Wasm/Webpack.
val bundleWebUi = providers.gradleProperty("mimir.bundleWebUi").map { it.toBoolean() }.orElse(false).get() ||
    gradle.startParameter.taskNames.any { taskName ->
        val simple = taskName.substringAfterLast(':')
        simple.contains("package", ignoreCase = true) ||
            simple.contains("Distributable", ignoreCase = true) ||
            simple in setOf("installDist", "distTar", "distZip", "assembleDist")
    }
if (bundleWebUi) {
    evaluationDependsOn(":apps:web-ui")
    tasks.processResources {
        from(project(":apps:web-ui").tasks.named("wasmJsBrowserDistribution")) { into("static") }
    }
}
```

- [ ] **Step 2: Добавить явную зависимость `@js-joda/core` в `apps/web-ui/build.gradle.kts` и связать `checkExtension` с `jsBrowserTest` в `apps/browser-extension/build.gradle.kts`**

1. В `apps/web-ui/build.gradle.kts` внутрь `wasmJsMain.dependencies` добавить:
   ```kotlin
   implementation(npm("@js-joda/core", "3.2.0"))
   ```
2. В `apps/browser-extension/build.gradle.kts` в `checkExtension` изменить `dependsOn(assembleExtension)` на:
   ```kotlin
   dependsOn(assembleExtension, "jsBrowserTest")
   ```

- [ ] **Step 3: Зарегистрировать `ciJvmTests` и порядок Yarn-задач в корневом `build.gradle.kts`, добавить `test:fast` и `ci:fast` в `mise.toml`**

1. В конец корневого `build.gradle.kts` добавить:
   ```kotlin
   // Быстрый агрегатор всех JVM-тестов (включая build-logic) без сборки Wasm/JS/Webpack/Android APK.
   tasks.register("ciJvmTests") {
       group = "verification"
       description = "Прогнать все JVM-тесты всех модулей и build-logic без Wasm/JS-бандлов"
       dependsOn(gradle.includedBuild("build-logic").task(":test"))
       subprojects.filter { it.name != "android" }.forEach { sub ->
           sub.tasks.matching { it.name in setOf("test", "jvmTest", "desktopTest") }.all {
               dependsOn(this)
           }
       }
   }

   tasks.matching { it.name == "check" }.configureEach {
       dependsOn(gradle.includedBuild("build-logic").task(":test"))
   }

   // Исключаем параллельный старт двух процессов Yarn на Windows (--mutex network race).
   tasks.matching { it.name == "kotlinWasmNpmInstall" }.configureEach {
       mustRunAfter(tasks.matching { it.name == "kotlinWasmToolingSetup" })
   }
   ```
2. В `mise.toml` после `[tasks.test]` добавить:
   ```toml
   [tasks."test:fast"]
   description = "Быстрый прогон всех JVM-тестов и build-logic (без Wasm/JS/Android)"
   run = "./gradlew ciJvmTests"
   run_windows = "gradlew.bat ciJvmTests"

   [tasks."ci:fast"]
   description = "Быстрая локальная предпроверка перед push (токены + actionlint + ciJvmTests)"
   run = "node scripts/generate-tokens.mjs --check && actionlint && ./gradlew ciJvmTests"
   run_windows = "node scripts/generate-tokens.mjs --check && gradlew.bat ciJvmTests"
   ```

- [ ] **Step 4: Проверить граф задач через `--dry-run`**

Run:
```bash
rtk ./gradlew ciJvmTests --dry-run
rtk ./gradlew :apps:desktop:packageUberJarForCurrentOS --dry-run
rtk ./gradlew :apps:daemon:installDist --dry-run
rtk ./gradlew :apps:browser-extension:checkExtension --dry-run
```
Expected:
1. В выводе `ciJvmTests --dry-run` присутствуют `:build-logic:test`, `:libs:core:jvmTest`, `:libs:drive:jvmTest`, `:libs:client:jvmTest`, `:libs:runtime:jvmTest`, `:libs:ui:desktopTest`, `:apps:daemon:test`, `:apps:desktop:test` и **отсутствует** `:apps:web-ui:wasmJsBrowserDistribution`.
2. В выводах `packageUberJarForCurrentOS --dry-run` и `installDist --dry-run` **присутствует** `:apps:web-ui:wasmJsBrowserDistribution`.
3. В выводе `checkExtension --dry-run` **присутствует** `:apps:browser-extension:jsBrowserTest`.

---

### Task 2: Реструктуризация `.github/workflows/ci.yml`, `.github/workflows/release.yml` и обновление документации

**Files:**
- Modify: `.github/workflows/ci.yml:1-178`
- Modify: `.github/workflows/release.yml:75-125`
- Modify: `AGENTS.md`
- Modify: `.agents/skills/mimir-build-verify/SKILL.md`

**Interfaces:**
- Consumes: `:ciJvmTests`, `-Pmimir.bundleWebUi=true` из Task 1.

- [ ] **Step 1: Обновить `.github/workflows/ci.yml`**

В `.github/workflows/ci.yml`:
1. В `on:` добавить:
   ```yaml
     schedule:
       - cron: '0 3 * * *'
   ```
2. В `concurrency:` установить:
   ```yaml
   concurrency:
     group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
     cancel-in-progress: ${{ github.event_name == 'pull_request' }}
   ```
3. В `detect-changes`:
   - Добавить outputs `package_msi: ${{ steps.matrix.outputs.package_msi }}`.
   - После `Verify design tokens are in sync` добавить шаг валидации workflow-файлов через `actionlint`:
     ```yaml
           - name: Lint GitHub Actions workflows
             run: |
               curl -sSfL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash | bash -s -- 1.7.7 /tmp
               /tmp/actionlint -color
     ```
   - В `dorny/paths-filter@v4` дополнить `core_or_libs` путями `'gradlew'`, `'gradlew.bat'`, `'.github/scripts/**'`, `'.github/workflows/**'` и добавить фильтр `msi_packaging`:
     ```yaml
                 msi_packaging:
                   - 'apps/desktop/**'
                   - 'apps/daemon/**'
                   - 'packaging/windows/**'
                   - 'build-logic/**'
                   - 'gradle/**'
                   - 'build.gradle.kts'
                   - 'settings.gradle.kts'
                   - 'gradle.properties'
                   - 'mise.toml'
                   - 'gradlew'
                   - 'gradlew.bat'
                   - '.github/workflows/ci.yml'
     ```
   - В шаге `id: matrix` передать `MSI_PACKAGING: ${{ steps.filter.outputs.msi_packaging }}` и вычислить `is_release_pr`, `package_msi`, `os_matrix`:
     - При `HEAD_REF == release-please--*`: `is_release_pr=true`, `package_msi=false`, `os_matrix=["ubuntu-latest"]`.
     - При `EVENT_NAME == "schedule"` или (`EVENT_NAME == "workflow_dispatch"` и `RUN_MACOS == "true"`): `is_release_pr=false`, `package_msi=true`, `os_matrix=["ubuntu-latest","windows-latest","macos-latest"]`.
     - Иначе: `is_release_pr=false`; `package_msi=true` если `EVENT_NAME == "push" || EVENT_NAME == "workflow_dispatch" || MSI_PACKAGING == "true"`, иначе `false`; `os_matrix=["ubuntu-latest","windows-latest"]` если `CORE_OR_LIBS == "true" || DESKTOP == "true"`, иначе `["ubuntu-latest"]`.
4. В джобе `build`:
   - Заменить монолитный шаг `Build and verify` на два шага:
     - Шаг 1 `Fast JVM tests (ciJvmTests)` (выполняется, когда `IS_RELEASE_PR != 'true'` и это не узкий `android`-only / `js_or_web`-only прогон):
       `./gradlew ciJvmTests`
     - Шаг 2 `Full build and packaging`:
       - `IS_RELEASE_PR == "true"` → `./gradlew -p build-logic test && ./gradlew :apps:desktop:help`
       - `runner.os == "Windows"` → если `PACKAGE_MSI == "true"`, выполнить `./gradlew :apps:desktop:packageMsi -Pmimir.bundleWebUi=true`, иначе вывести сообщение о пропуске упаковки MSI на PR.
       - `runner.os == "macOS"` → `./gradlew :apps:desktop:packageUberJarForCurrentOS -Pmimir.bundleWebUi=true`
       - Только `ANDROID == "true"` → `./gradlew -p build-logic test && ./gradlew :apps:android:testDebugUnitTest :apps:android:assembleDebug`
       - Только `JS_OR_WEB == "true"` → `./gradlew -p build-logic test && ./gradlew :apps:obsidian-plugin:checkPlugin :apps:browser-extension:checkExtension :apps:web-ui:wasmJsBrowserDistribution :libs:ui:desktopTest`
       - Иначе (`ubuntu-latest`) → `./gradlew build --continue`
5. Добавить ночные dry-run джобы `arch-dry-run` и `docker-dry-run` с `if: github.event_name == 'schedule'` (сборка пакета Arch через `.github/scripts/build-arch.sh` и Docker-образа с `push: false`).

- [ ] **Step 2: Добавить проверку статуса CI и `-Pmimir.bundleWebUi=true` в `.github/workflows/release.yml`**

1. В `.github/workflows/release.yml` в джобе `build` после шага `Tag matches mimir.version` добавить:
   ```yaml
         - name: Verify CI status for release commit
           if: ${{ inputs.dry_run != true }}
           env:
             GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
           run: |
             sha=$(git rev-parse HEAD)
             failed=$(gh api "repos/${{ github.repository }}/commits/$sha/check-runs" --jq '[.check_runs[] | select((.name | startswith("build (")) and .conclusion != "success" and .conclusion != "skipped")] | length')
             if [ "$failed" -gt 0 ]; then
               echo "CI check-runs failed or incomplete for commit $sha"
               exit 1
             fi
   ```
2. В шаге `Build` заменить команду на:
   ```yaml
           run: ./gradlew ${{ matrix.task }} -Pmimir.bundleWebUi=true
   ```

- [ ] **Step 3: Синхронизировать `AGENTS.md` и `.agents/skills/mimir-build-verify/SKILL.md`**

Обновить описание `ci.yml` (`dorny/paths-filter@v4`, `ciJvmTests`, условная сборка `packageMsi`, ночной `schedule`) и добавить команды `mise run test:fast` и `mise run ci:fast` в таблицу команд `.agents/skills/mimir-build-verify/SKILL.md`.

- [ ] **Step 4: Проверить синтаксис GitHub Actions и дизайн-токенов**

Run:
```bash
rtk actionlint
rtk node scripts/generate-tokens.mjs --check
```
Expected: `actionlint` завершается с кодом `0` без предупреждений; `Design tokens are up to date.`

---

### Task 3: Прогон локального контура `mise run ci:fast`, независимое ревью двух экспертов и финальный коммит

**Files:**
- Verify & Commit: `apps/daemon/build.gradle.kts`, `apps/web-ui/build.gradle.kts`, `apps/browser-extension/build.gradle.kts`, `build.gradle.kts`, `mise.toml`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `AGENTS.md`, `.agents/skills/mimir-build-verify/SKILL.md`

- [ ] **Step 1: Выполнить реальный прогон `rtk mise run ci:fast` и замерить время**

Run: `rtk mise run ci:fast`
Expected: Токены, `actionlint` и все JVM-тесты (`ciJvmTests`) проходят успешно (`BUILD SUCCESSFUL`), задачи Wasm/Webpack не вызываются.

- [ ] **Step 2: Провести перекрёстное ревью двумя независимыми субагентами (`research` / `flash`)**

1. **Эксперт 1 (GitHub Actions & Release Reviewer):** проверяет только `git diff -- .github/workflows/` и вывод `actionlint` на предмет ошибок матриц, кэширования, прав доступа и условий запуска.
2. **Эксперт 2 (Gradle & Local Testing Reviewer):** проверяет только `git diff -- '*.gradle.kts' mise.toml` на предмет полноты локального отлова ошибок, сохранности Configuration Cache и включения `static/` в релизные дистрибутивы.
3. При наличии замечаний — устранить их и повторить `rtk mise run ci:fast`.

- [ ] **Step 3: Обновить базу знаний `context-init` и зафиксировать коммит в `main`**

Run:
```bash
rtk context-init check --compact .
rtk git add -A && rtk git commit -m "ci: decouple Wasm from JVM tests, add ciJvmTests and optimize workflows"
```
Expected: Чистый `rtk git status -sb`, `context-init check` без дрейфа.
