# Спецификация: Быстрое локальное тестирование и оптимизация GitHub Actions CI/Release

**Дата:** 2026-11-05  
**Статус:** Утверждено к планированию и исполнению  
**Связанные документы:** `docs/superpowers/specs/2026-10-23-mimir-design.md`, `docs/superpowers/specs/2026-11-04-monorepo-structure-and-ci-pipeline-design.md`

---

## 1. Цель и назначение (Intent & Why)

1. **Быстрая локальная обратная связь до `git push`:** Разработчик на Arch Linux должен иметь возможность за 10–30 секунд (при прогретом Gradle Daemon) проверить все JVM-тесты всех модулей, тесты `build-logic`, синхронизацию дизайн-токенов и синтаксис GitHub Actions (`actionlint`) одной командой (`mise run ci:fast` / `mise run test:fast`), не ожидая многоминутной компиляции Kotlin/Wasm, оптимизатора Binaryen (`wasm-opt`) и Webpack.
2. **Стабильность сборки на Windows и в Release:** Полностью устранить сбой `:apps:web-ui:wasmJsBrowserProductionWebpack` (`Module not found: Error: Can't resolve '@js-joda/core'`) и гонку параллельных процессов Yarn (`:kotlinWasmToolingSetup` vs `:kotlinWasmNpmInstall`) на Windows.
3. **Эффективность и полнота покрытия CI:**
   - Сократить время PR-проверок на Windows с 5–9 минут до 1.5–3 минут за счёт отвязки Wasm от JVM-тестов и запуска `packageMsi` только там, где затронута упаковка/десктоп или идёт слияние в `main`.
   - Закрыть все дыры в покрытии: вернуть платформозависимые JVM-тесты на Windows (`VaultWatcher`, `FileSystemLocalVault`, `JsonSyncStateStore`, `FileTokenStorage`, `:apps:daemon:test`), включить `:apps:browser-extension:jsBrowserTest` в `:apps:browser-extension:checkExtension`, включить `:build-logic:test` в общий цикл `check`/`ciJvmTests`, расширить `paths-filter`.
   - Прекратить потерю кэша Gradle в `main` из-за `cancel-in-progress: true`.
   - Защитить `release.yml` от публикации непроверенного коммита.

---

## 2. Цели, не-цели и анти-цели

- **Цели (Goals):**
  - Отвязать `:apps:web-ui:wasmJsBrowserDistribution` от `processResources` модуля `:apps:daemon` при прогоне тестов, сохранив обязательное включение `static/` при сборке дистрибутивов (`packageMsi`, `packageUberJarForCurrentOS`, `createDistributable`, `installDist`, `distTar`, `distZip`) и при `-Pmimir.bundleWebUi=true`.
  - Ввести Gradle-агрегатор `ciJvmTests` (включающий `build-logic:test` и все JVM/Desktop-тесты подпроектов).
  - Ввести локальные задачи `mise run test:fast` и `mise run ci:fast`.
  - Разделить шаги внутри `build`-джоба `ci.yml`: сначала быстрый `ciJvmTests`, затем полная проверка/упаковка (с переиспользованием локального состояния Gradle без повторной компиляции).
  - Добавить ночной прогон (`schedule`) с полной матрицей (`ubuntu-latest`, `windows-latest`, `macos-latest`, `packageMsi`, dry-run `arch` и `docker`).
- **Не-цели (Non-Goals):**
  - Изменение бизнес-логики синхронизации, криптографии или UI-экранов.
  - Генерация секретов `ANDROID_KEYSTORE_*` или `MIMIR_BUILD_GOOGLE_DEVICE_*` (управляются владельцем репозитория в GitHub Secrets).
- **Анти-цели (Anti-Goals):**
  - Вынос Windows-джоба за последовательный гейт после Linux (увеличивает латентность PR, так как 7 из 9 исторических сбоев CI были специфичны для Windows).
  - Разбиение Linux-пайплайна на отдельные параллельные VM-джобы для PR (на PR кэш Gradle работает в режиме `cache-read-only: true`, что приведёт к повторной компиляции изменённого кода в каждой джобе).
  - Хардкод списка модулей для JVM-тестов в `.github/workflows/ci.yml` вместо задачи в Gradle.

---

## 3. Полная диагностика первопричин (Справочник для исполнителей — без повторного исследования)

| # | Проблема | Точное место в коде | Первопричина |
|---|---|---|---|
| 1 | `:apps:daemon:test` и `:apps:desktop:test` тянут полную сборку Wasm (3–3.5 мин на Windows) | `apps/daemon/build.gradle.kts:60-64` | `tasks.processResources` безусловно включает `from(project(":apps:web-ui").tasks.named("wasmJsBrowserDistribution")) { into("static") }`. Модуль `:apps:desktop` зависит от `project(":apps:daemon")`. Ни один тест не обращается к `static/` (используется только в `Routes.kt` для отдачи статики в рантайме). |
| 2 | Падение `:apps:web-ui:wasmJsBrowserProductionWebpack` на Windows (`Can't resolve '@js-joda/core'`) | `apps/web-ui/build.gradle.kts:16-23`, `build.gradle.kts` | 1) В KGP 2.4.21 `YarnWorkspaces` записывает локальные workspace-пути через `File.relativeTo().path` (на Windows — с `\`), из-за чего при частичном восстановлении кэша `GradleNodeModulesCache` транзитивная зависимость `@js-joda/core` (от `kotlinx-datetime` в `:libs:core`) не резолвится для `mimir-apps-web-ui`.<br>2) `:kotlinWasmToolingSetup` и `:kotlinWasmNpmInstall` стартуют параллельно с разницей 7 мс и конкурируют за глобальный кэш Yarn и `--mutex network`. |
| 3 | Пропуск Karma-тестов браузерного расширения при правках в `apps/browser-extension/**` | `apps/browser-extension/build.gradle.kts:94-99`, `.github/workflows/ci.yml:165` | Ветка `JS_OR_WEB` в `ci.yml` вызывает `:apps:browser-extension:checkExtension`, который зависит только от `assembleExtension`, но не от `jsBrowserTest`. |
| 4 | `:build-logic:test` не запускается при обычной сборке и правках `build-logic` | `build.gradle.kts`, `.github/workflows/ci.yml:157` | Включённый билд `build-logic` вызывается только в ветке `IS_RELEASE_PR="true"`. Корневой `check`/`build` не делегирует тесты в `gradle.includedBuild("build-logic").task(":test")`. |
| 5 | На Windows не запускаются платформозависимые тесты ФС и демона | `.github/workflows/ci.yml:159` | На Windows выполняется только `./gradlew :apps:desktop:test :apps:desktop:packageMsi`. Тесты `VaultWatcher`, `FileSystemLocalVault`, `JsonSyncStateStore`, `FileTokenStorage` и `:apps:daemon:test` на Windows пропускаются. |
| 6 | Потеря прогретого кэша Gradle в ветке `main` | `.github/workflows/ci.yml:29-31` | `cancel-in-progress: true` отменяет `push`-прогоны в `main` (отменено 7 из 30 прогонов). Так как только `main` имеет право записи в кэш `setup-gradle@v6`, PR остаются без актуального кэша. |
| 7 | Неполный фильтр путей и лишняя сборка MSI на каждый PR | `.github/workflows/ci.yml:60-87` | `paths-filter` не включает `gradlew`, `gradlew.bat`, `.github/scripts/**`, `.github/workflows/**`. Нет отдельного фильтра `msi_packaging`, из-за чего любое изменение в `libs/**` запускает долгую сборку `packageMsi` (WiX + `jlink` + Wasm) на PR. |
| 8 | Отсутствие валидации YAML до и во время CI | `.github/workflows/ci.yml`, `mise.toml` | `actionlint` установлен в окружении (`mise`), но не включён в локальный предпроверочный таск и в `detect-changes` джоб CI. |
| 9 | Публикация релиза без проверки статуса CI | `.github/workflows/release.yml:78-86` | `release.yml` проверяет совпадение тега с `mimir.version`, но не проверяет статус `check-runs` коммита в CI при `dry_run != true`. |

---

## 4. Детальное техническое решение

### 4.1. Условное подключение Wasm-бандла в `apps/daemon/build.gradle.kts`

В `apps/daemon/build.gradle.kts` заменить безусловный блок `evaluationDependsOn(":apps:web-ui")` / `tasks.processResources` на условный:
- Свойство `-Pmimir.bundleWebUi=true` принудительно включает бандл.
- Если свойство не задано явно, бандл включается автоматически, когда среди запрошенных задач (`gradle.startParameter.taskNames`) есть упаковочные задачи (`package*`, `*Distributable*`, `installDist`, `distTar`, `distZip`, `assembleDist`).
- При запуске `test`, `jvmTest`, `desktopTest`, `ciJvmTests`, `check` бандл `:apps:web-ui:wasmJsBrowserDistribution` в `:apps:daemon:processResources` НЕ подключается.
- При этом полный `./gradlew build` на Linux по-прежнему собирает и проверяет `:apps:web-ui:wasmJsBrowserDistribution`, так как в самом модуле `:apps:web-ui` задача `assemble` зависит от `wasmJsBrowserDistribution`.

### 4.2. Фиксация `@js-joda/core` и порядка Yarn-задач для Windows Wasm

1. В `apps/web-ui/build.gradle.kts` в блок `wasmJsMain.dependencies` добавить:
   ```kotlin
   implementation(npm("@js-joda/core", "3.2.0"))
   ```
2. В корневом `build.gradle.kts` исключить параллельный старт `:kotlinWasmNpmInstall` и `:kotlinWasmToolingSetup`:
   ```kotlin
   tasks.matching { it.name == "kotlinWasmNpmInstall" }.configureEach {
       mustRunAfter(tasks.matching { it.name == "kotlinWasmToolingSetup" })
   }
   ```

### 4.3. Агрегатор `ciJvmTests` и включение `:build-logic:test` и `jsBrowserTest`

1. В корневом `build.gradle.kts` зарегистрировать задачу `ciJvmTests`:
   - Зависит от `gradle.includedBuild("build-logic").task(":test")`.
   - Для всех подпроектов кроме `:apps:android` (чьи unit-тесты требуют Android SDK / Robolectric и идут отдельным таском `testDebugUnitTest`) зависит от всех задач с именами `test`, `jvmTest`, `desktopTest`.
   - Связать корневой/подпроектный жизненный цикл `check` с `gradle.includedBuild("build-logic").task(":test")`.
2. В `apps/browser-extension/build.gradle.kts` в задаче `checkExtension` указать:
   ```kotlin
   dependsOn(assembleExtension, "jsBrowserTest")
   ```

### 4.4. Быстрый локальный контур тестирования в `mise.toml`

Добавить в `mise.toml` две задачи:
- `mise run test:fast` → `./gradlew ciJvmTests` (все JVM-тесты всех модулей + `build-logic:test` без Wasm/JS/Android).
- `mise run ci:fast` → `node scripts/generate-tokens.mjs --check && actionlint && ./gradlew ciJvmTests` (полная локальная предпроверка перед `git push`: дизайн-токены + линтер GitHub Actions + все JVM/Desktop-тесты).

### 4.5. Схема `.github/workflows/ci.yml`

```text
detect-changes: токены (generate-tokens.mjs --check), actionlint, dorny/paths-filter@v4
 ├─ build (ubuntu-latest)
 │    1. Fast JVM tests: ./gradlew ciJvmTests
 │    2. Full verification: ./gradlew build --continue (или целевой набор для android-only / js_or_web-only PR)
 ├─ build (windows-latest) [параллельно с Linux, без гейта]
 │    1. Fast JVM & OS tests: ./gradlew ciJvmTests
 │    2. MSI packaging (только push в main, schedule, workflow_dispatch или msi_packaging == 'true'):
 │       ./gradlew :apps:desktop:packageMsi -Pmimir.bundleWebUi=true
 ├─ build (macos-latest) [только schedule или workflow_dispatch с run_macos=true]
 │    1. Fast JVM & OS tests: ./gradlew ciJvmTests
 │    2. UberJar packaging: ./gradlew :apps:desktop:packageUberJarForCurrentOS -Pmimir.bundleWebUi=true
 └─ nightly dry-run (только schedule):
      ├─ arch-dry-run (.github/scripts/build-arch.sh в контейнере archlinux:latest)
      └─ docker-dry-run (docker/build-push-action@v7 с push: false)
```

Ключевые правила `ci.yml`:
- `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`.
- Фильтр `core_or_libs` дополнен `gradlew`, `gradlew.bat`, `.github/scripts/**`, `.github/workflows/**`.
- Фильтр `msi_packaging` включает `apps/desktop/**`, `apps/daemon/**`, `packaging/windows/**`, `build-logic/**`, `gradle/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `mise.toml`, `gradlew`, `gradlew.bat`, `.github/workflows/ci.yml`.

### 4.6. Защита `.github/workflows/release.yml`

1. В шаге `Build` передавать `-Pmimir.bundleWebUi=true` (`./gradlew ${{ matrix.task }} -Pmimir.bundleWebUi=true`).
2. В джобе `build` при `inputs.dry_run != true` добавить шаг `Verify CI status for release commit`, который через `gh api repos/${{ github.repository }}/commits/$sha/check-runs` проверяет, что среди завершённых `build (` чек-ранов нет неуспешных (`conclusion != "success" && conclusion != "skipped"`).

---

## 5. Инварианты Mimir

1. **Платформы:**
   - **Linux (`ubuntu-latest` / Arch):** локально доступны быстрые `mise run test:fast` и `mise run ci:fast`; в CI сначала выполняется быстрый `ciJvmTests`, затем полный `./gradlew build --continue`. Сборка без Android SDK (`AndroidSdk.isAvailable`) не затрагивается (`ciJvmTests` не включает `:apps:android`).
   - **Windows (`windows-latest`):** теперь прогоняет все платформозависимые JVM-тесты (`VaultWatcher`, `FileSystemLocalVault`, `JsonSyncStateStore`, `FileTokenStorage`, `:apps:daemon:test`, `:apps:desktop:test`) за ~1.5–3 мин без компиляции Wasm. Упаковка `packageMsi` с вшитым `web-ui` (`-Pmimir.bundleWebUi=true`) выполняется на `main`, `schedule`, `workflow_dispatch` и при изменениях `msi_packaging`.
   - **macOS (`macos-latest`):** проверяется в ночном `schedule` и по `workflow_dispatch` (`run_macos=true`) через `ciJvmTests` + `packageUberJarForCurrentOS -Pmimir.bundleWebUi=true`.
   - **Android, Docker, Arch, JS-клиенты:** `:apps:browser-extension:checkExtension` теперь гарантированно запускает `jsBrowserTest`; `arch` и `docker` проверяются каждую ночь в `schedule` (`arch-dry-run`, `docker-dry-run`).
2. **Дизайн:** агрегатор `ciJvmTests` и свойство `mimir.bundleWebUi` инкапсулированы в Gradle (`build.gradle.kts`, `apps/daemon/build.gradle.kts`), а не размазаны списком модулей по YAML.
3. **IO:** неприменимо к сетевому протоколу Drive; на уровне сборочного IO исключена холостая сборка 12 MiB Wasm-ассетов и Binaryen `wasm-opt` при каждом запуске JVM-тестов.
4. **UX:** неприменимо (пользовательский интерфейс не меняется; в релизных дистрибутивах MSI, Arch, Docker бандл `static/` сохраняется в полном объёме).
