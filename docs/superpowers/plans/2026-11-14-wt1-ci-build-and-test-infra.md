# Оптимизация CI/CD, Gradle Convention-плагинов и инфраструктуры тестов — Implementation Plan

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ СКИЛЛ: `superpowers:using-git-worktrees` → `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans` → `superpowers:verification-before-completion`. Шаги отмечаются чекбоксами (`- [ ]`). Прогресс фиксировать в `.superpowers/sdd/2026-11-14-wt1-ci-build-and-test-infra/progress.md`.

**Goal:** Ускорить прогон тестов и сборку за счёт параллельных форков JVM (`maxParallelForks`), перевода non-DOM KMP JS-тестов с Karma+Chromium на Node.js, дедупликации GitHub Actions (`setup-mimir` composite action), включения `win:test` в CI и оптимизации слоёв `Dockerfile` и `PKGBUILD`.

**Architecture:** Все изменения изолированы в контуре сборки и CI (`.github/**`, `build-logic/**`, `gradle/**`, `*/build.gradle.kts`, `packaging/**`, `scripts/win-vm/tests/**`) и не затрагивают исходный код модулей `src/`. Общая конфигурация Karma выносится в хелпер `build-logic`, а шаги инициализации окружения CI — в локальный composite action `.github/actions/setup-mimir/action.yml`.

**Tech Stack:** Gradle 9.8+, Kotlin Multiplatform 2.4.21, GitHub Actions, `jactionlint`, Docker Buildx, Arch `makepkg`, Bash.

**Spec:** `docs/superpowers/specs/2026-11-06-ci-build-test-improvements-design.md`, `docs/superpowers/specs/2026-11-07-windows-vm-test-flow-design.md`

**Worktree:** `.worktrees/wt-ci-build` (ветка `refactor/ci-and-build`)

## Global Constraints

- Все идентификаторы (`mimir.appName`, `mimir.version`, `mimir.group`, `mimir.pacmanName`, `mimir.githubOwner`, `mimir.githubRepo`) читаются исключительно из `gradle.properties`.
- Все версии зависимостей и SDK (`compileSdk`, `minSdk`, `targetSdk`, `@js-joda/core`) хранятся только в `gradle/libs.versions.toml`.
- Сборка без Android SDK (`env -u ANDROID_HOME -u ANDROID_SDK_ROOT` и без `local.properties`) обязана проходить с предупреждением `Android SDK not found: skipping Android targets`.
- Все терминальные команды выполняются строго с префиксом `rtk`; поиск файлов и текста — строго через `rtk fd` и `rtk rg` (запрет GNU `find` и `grep`).
- Configuration Cache (`org.gradle.configuration-cache=true`) и Build Cache (`org.gradle.caching=true`) не должны ломаться.

## Инварианты Mimir

1. **Платформы:**
   - **Linux (`ubuntu-latest` / Arch):** в `detect-changes` выполняется `mise run win:test`; при полном прогоне `build --continue` исключён дублирующий предварительный вызов `./gradlew ciJvmTests` и сброс `ANDROID_HOME` между шагами одного раннера.
   - **Windows (`windows-latest`):** продолжает выполнять `ciJvmTests` + условный `packageMsi` через общий `.github/actions/setup-mimir`.
   - **macOS (`macos-latest`):** использует общий `.github/actions/setup-mimir` при `schedule` и `workflow_dispatch`.
   - **Android (`:apps:android`):** единые версии `compileSdk`/`minSdk`/`targetSdk` из `libs.versions.toml`; Robolectric-тесты работают с `maxParallelForks = 2` и `forkEvery = 0`.
   - **Docker и Arch:** `Dockerfile` кэширует слой Gradle-скриптов отдельно от исходников `libs/` и `apps/`; `build-arch.sh` формирует валидный `url=https://github.com/<owner>/<repo>` в `PKGBUILD`.
   - **JS-клиенты:** библиотечные модули (`:libs:core`, `:libs:drive`, `:libs:client`) гоняют JS-тесты в `nodejs` без старта Chromium; DOM-зависимые `:apps:obsidian-plugin` и `:apps:browser-extension` используют единый `configureKarmaHeadless()` из `build-logic`.
2. **Дизайн:**
   - Расширение convention-плагинов `mimir.jvm`, `mimir.kmp-library`, `mimir.android-support` и хелпера `dev.mimir.build.KarmaConfig` в `build-logic`.
   - Выделение `.github/actions/setup-mimir/action.yml` для устранения дублирования между `ci.yml` и `release.yml`.
3. **IO:**
   - Неприменимо к Drive/vault; в CI экономится 3 запуска headless Chromium на каждый прогон `allTests`, 1 лишний старт Gradle на Linux-раннере и 28 вызовов `git init` в `win:test`.
4. **UX:**
   - Неприменимо к экранам приложения; в пакете Arch Linux (`pacman -Qi mimir`) отображается корректный URL репозитория вместо `https://github.com/`.

## Review Focus

1. **Правка только `scripts/win-vm/**` или `.agents/**` в PR:** `detect-changes` обязан прогнать `tokens:check`, `skills:check`, `win:test` и `jactionlint`, но не должен запускать 15-минутную матрицу `build` (в ветке `else` `ci.yml`).
2. **Линтер GitHub Actions (`jactionlint --profile correctness`):** локальный composite action `.github/actions/setup-mimir/action.yml` и обновлённые `ci.yml`/`release.yml` обязаны проходить `jactionlint` с 0 предупреждений.
3. **Configuration Cache при настройке `Test` и `ciJvmTests`:** замена `.all {}` в `build.gradle.kts` и настройка `maxParallelForks` обязаны сохранять `Configuration cache entry stored/reused`.
4. **Сборка без Android SDK:** чтение `compileSdk`/`minSdk` из `libs.versions.toml` в `mimir.android-support.gradle.kts` не должно требовать наличия Android SDK при его отсутствии.
5. **Чистота `PKGBUILD` после `build-arch.sh`:** подстановка `url` должна корректно экранировать `/` в `sed`.

## Структура файлов

```
.github/actions/setup-mimir/action.yml                         (созд.: общий setup Windows/Java/mise/Gradle)
.github/workflows/ci.yml                                       (изм.: win:test, фильтр scripts_or_skills, одиночный gradlew, setup-mimir)
.github/workflows/release.yml                                  (изм.: setup-mimir, удаление checks: read)
.github/workflows/release-please.yml                           (изм.: удаление checks: read)
gradle/libs.versions.toml                                      (изм.: версии android-compileSdk/minSdk/targetSdk, jsJodaCore)
gradle/karma.config.d/mocha-timeout.js                         (изм.: таймаут 20000 мс)
build.gradle.kts                                               (изм.: ленивый dependsOn в ciJvmTests)
build-logic/src/main/kotlin/dev/mimir/build/KarmaConfig.kt     (созд.: configureKarmaHeadless и googleSecret)
build-logic/src/main/kotlin/mimir.jvm.gradle.kts               (изм.: maxParallelForks)
build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts       (изм.: js nodejs вместо Karma, maxParallelForks)
build-logic/src/main/kotlin/mimir.android-support.gradle.kts   (изм.: compileSdk/minSdk из libs.versions.toml)
libs/runtime/build.gradle.kts                                  (изм.: group/version, maxParallelForks)
libs/ui/build.gradle.kts                                       (изм.: group/version)
apps/web-ui/build.gradle.kts                                   (изм.: group/version, jsJodaCore из libs.versions.toml)
apps/obsidian-plugin/build.gradle.kts                          (изм.: configureKarmaHeadless, googleSecret из build-logic)
apps/browser-extension/build.gradle.kts                        (изм.: configureKarmaHeadless, googleSecret из build-logic)
apps/android/build.gradle.kts                                  (изм.: SDK версии из libs.versions.toml, maxParallelForks = 2, forkEvery = 0)
packaging/docker/Dockerfile                                    (изм.: разделение слоёв Gradle и исходников)
packaging/arch/build-arch.sh                                   (изм.: подстановка url из gradle.properties)
scripts/win-vm/tests/run.sh                                    (изм.: rg -o вместо grep -oE, шаблонный git-репо)
scripts/win-vm/tests/helpers.sh                                (изм.: копирование шаблонного репо вместо git init)
```

---

### Task 1: Ускорение `scripts/win-vm/tests` и замена `grep` на `rg`

**Files:**
- Modify: `scripts/win-vm/tests/run.sh:1-60`
- Modify: `scripts/win-vm/tests/helpers.sh:20-45`

**Interfaces:**
- Produces: переменная окружения `WINVM_TEST_TEMPLATE_REPO`, создаваемая один раз в `run.sh` и копируемая через `cp -a` в `make_repo()`.

- [ ] **Step 1: Проверить наличие `grep` в `scripts/win-vm/tests/run.sh` и замерить текущее время `mise run win:test`**

Run: `rtk rg -n 'grep' scripts/win-vm/tests/run.sh`
Expected: строка 54 с `grep -oE '^test_[A-Za-z0-9_]+' "$file"`.

- [ ] **Step 2: В `scripts/win-vm/tests/run.sh` создать одноразовый шаблонный git-репозиторий и заменить `grep -oE` на `rg -o`**

В `scripts/win-vm/tests/run.sh`:
- Перед циклом по файлам создать `WINVM_TEST_TEMPLATE_REPO="$(mktemp -d)"` (`trap 'rm -rf "$WINVM_TEST_TEMPLATE_REPO"' EXIT`), инициализировать в нём git-репозиторий с базовыми файлами (`README.md`, `tracked.txt`, `.gitignore`) один раз.
- На строке 54 заменить `grep -oE '^test_[A-Za-z0-9_]+' "$file"` на `rg -o '^test_[A-Za-z0-9_]+' "$file"`.
В `scripts/win-vm/tests/helpers.sh` в `make_repo()`:
- Если задан `WINVM_TEST_TEMPLATE_REPO` и каталог существует, копировать `cp -a "$WINVM_TEST_TEMPLATE_REPO/." "$repo/"` вместо повторного `git init` и `git commit`/конфига.

- [ ] **Step 3: Запустить тесты `win:test` и проверить отсутствие `grep`**

Run: `rtk mise run win:test && rtk rg 'grep' scripts/win-vm/tests/`
Expected: 37/37 тестов `PASS`, `rg 'grep'` возвращает пустой вывод (код 1).

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add scripts/win-vm/tests/run.sh scripts/win-vm/tests/helpers.sh
rtk oco -y
```

---

### Task 2: Унификация `build-logic`, `libs.versions.toml`, `maxParallelForks` и перевод JS-тестов библиотек на Node.js

**Files:**
- Modify: `gradle/libs.versions.toml:1-26`
- Create: `build-logic/src/main/kotlin/dev/mimir/build/KarmaConfig.kt`
- Modify: `build-logic/src/main/kotlin/mimir.jvm.gradle.kts`
- Modify: `build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts`
- Modify: `build-logic/src/main/kotlin/mimir.android-support.gradle.kts`
- Modify: `build.gradle.kts:33-45`
- Modify: `libs/runtime/build.gradle.kts`
- Modify: `libs/ui/build.gradle.kts`
- Modify: `apps/web-ui/build.gradle.kts`
- Modify: `apps/obsidian-plugin/build.gradle.kts`
- Modify: `apps/browser-extension/build.gradle.kts`
- Modify: `apps/android/build.gradle.kts`
- Modify: `gradle/karma.config.d/mocha-timeout.js`

**Interfaces:**
- Produces:
  - `fun KotlinJsBrowserDsl.configureKarmaHeadless(rootProject: Project)` и `fun Project.googleSecret(name: String): Provider<String>` в пакете `dev.mimir.build`
  - Версии `androidCompileSdk = "37"`, `androidMinSdk = "26"`, `androidTargetSdk = "36"`, `jsJodaCore = "3.2.0"` в `gradle/libs.versions.toml`

- [ ] **Step 1: Написать юнит-тест в `build-logic/src/test/kotlin/dev/mimir/build/KarmaConfigTest.kt` (или проверить существующие тесты `build-logic`)**

Добавить в `build-logic/src/test/` проверку резолва `googleSecret` (приоритет `MIMIR_BUILD_GOOGLE_*` над `MIMIR_GOOGLE_*` и дефолт `""`).
Run: `rtk ./gradlew -p build-logic test`

- [ ] **Step 2: Добавить версии SDK и `jsJodaCore` в `gradle/libs.versions.toml` и создать `KarmaConfig.kt`**

В `gradle/libs.versions.toml` (`[versions]`):
```toml
androidCompileSdk = "37"
androidMinSdk = "26"
androidTargetSdk = "36"
jsJodaCore = "3.2.0"
```
В `build-logic/src/main/kotlin/dev/mimir/build/KarmaConfig.kt`:
- Вынести `fun KotlinJsBrowserDsl.configureKarmaHeadless(project: Project)` (`useKarma { useChromeHeadless(); useConfigDirectory(project.rootProject.file("gradle/karma.config.d")) }` + автоопределение `/usr/bin/chromium`).
- Вынести `fun Project.googleSecret(name: String): Provider<String>`.

- [ ] **Step 3: Обновить convention-плагины и `build.gradle.kts` модулей**

1. В `mimir.jvm.gradle.kts` и `mimir.kmp-library.gradle.kts` добавить:
   ```kotlin
   tasks.withType<Test>().configureEach {
       maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
   }
   ```
2. В `mimir.kmp-library.gradle.kts` заменить блок `js { browser { ... } }` на:
   ```kotlin
   js {
       nodejs {
           testTask {
               timeout.set(Duration.ofMinutes(1))
           }
       }
   }
   ```
3. В `mimir.android-support.gradle.kts` и `apps/android/build.gradle.kts` читать `compileSdk`, `minSdk`, `targetSdk` из каталога `libs.versions.android*`; в `apps/android/build.gradle.kts:144` добавить `maxParallelForks = 2` и `forkEvery = 0`.
4. В `libs/runtime/build.gradle.kts`, `libs/ui/build.gradle.kts`, `apps/web-ui/build.gradle.kts` задать `group` и `version` из `providers.gradleProperty(...)`; в `apps/web-ui/build.gradle.kts:22` использовать `libs.versions.jsJodaCore.get()`.
5. В `apps/obsidian-plugin/build.gradle.kts` и `apps/browser-extension/build.gradle.kts` использовать `configureKarmaHeadless(project)` и `googleSecret(...)` из `dev.mimir.build`.
6. В корневом `build.gradle.kts:38-43` упростить `ciJvmTests` без императивного `.all {}`:
   ```kotlin
   subprojects.filter { it.name != "android" }.forEach { sub ->
       dependsOn(sub.tasks.matching { it.name in setOf("test", "jvmTest", "desktopTest") })
   }
   ```
7. В `gradle/karma.config.d/mocha-timeout.js` установить `config.client.mocha.timeout = 20000`.

- [ ] **Step 4: Проверить сборку и тесты (`build-logic`, `ciJvmTests`, `jsNodeTest`, `checkPlugin`, `checkExtension`)**

Run: `rtk ./gradlew -p build-logic test && rtk ./gradlew ciJvmTests :libs:core:jsTest :libs:drive:jsTest :libs:client:jsTest :apps:obsidian-plugin:checkPlugin :apps:browser-extension:checkExtension`
Expected: `BUILD SUCCESSFUL`, `:libs:core:jsNodeTest` выполняется в Node.js без запуска Karma/Chrome.

- [ ] **Step 5: Закоммитить изменения**

```bash
rtk git add gradle/ build-logic/ build.gradle.kts libs/ apps/
rtk oco -y
```

---

### Task 3: Оптимизация `.github/workflows/ci.yml`, `release.yml`, `release-please.yml` и composite action `setup-mimir`

**Files:**
- Create: `.github/actions/setup-mimir/action.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `.github/workflows/release-please.yml`

**Interfaces:**
- Produces: `.github/actions/setup-mimir/action.yml` (включающий настройку Windows reg/git, `jdx/mise-action@v5`, экспорт `JAVA_HOME` и `gradle/actions/setup-gradle@v6`).

- [ ] **Step 1: Создать `.github/actions/setup-mimir/action.yml` и подключить его в `ci.yml` и `release.yml`**

В `.github/actions/setup-mimir/action.yml` оформить `runs: using: "composite"` с 4 шагами (Windows env configuration при `runner.os == 'Windows'`, `jdx/mise-action@v5`, `Configure Java environment from mise`, `gradle/actions/setup-gradle@v6`). Заменить дублирующиеся блоки в `ci.yml:173-191` и `release.yml:95-114` на `- uses: ./.github/actions/setup-mimir`.

- [ ] **Step 2: Добавить `win:test` и фильтр `scripts_or_skills` в `ci.yml`, убрать двойной вызов `./gradlew` и лишние `checks: read`**

1. В `ci.yml`:
   - В `detect-changes` (`строка 65`): `install_args: node jactionlint shellcheck jq`.
   - Добавить шаг `Verify Windows VM host CLI tests`: `run: mise run win:test` (с `MISE_TASK_RUN_AUTO_INSTALL: "false"`).
   - В `dorny/paths-filter` добавить фильтр `scripts_or_skills` (`scripts/**`, `.agents/**`, `.claude/**`). В шаге `matrix`: если `CORE_OR_LIBS != 'true' && DESKTOP != 'true' && ANDROID != 'true' && JS_OR_WEB != 'true'` и изменились только `scripts_or_skills`, выставлять `os_matrix=[]` (и `if: needs.detect-changes.outputs.os_matrix != '[]'` на джобе `build`).
   - На шаге `Fast JVM tests (ciJvmTests)` (`строка 200`): запускать его только при `runner.os != 'Linux'` (на Windows и macOS, где следом идёт упаковка без тестов), а на Linux выполнять единый шаг `Full build and packaging` (объединив двойные `./gradlew -p build-logic test && ./gradlew ...` на строках `212, 222, 224` в один вызов `./gradlew`).
2. В `release.yml:68` и `release-please.yml:37` удалить `checks: read`.

- [ ] **Step 3: Проверить все workflows линтером `jactionlint`**

Run: `rtk jactionlint --profile correctness`
Expected: 0 ошибок, код выхода 0.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add .github/
rtk oco -y
```

---

### Task 4: Оптимизация слоёв `packaging/docker/Dockerfile` и `url` в `packaging/arch/build-arch.sh`

**Files:**
- Modify: `packaging/docker/Dockerfile:10-15`
- Modify: `packaging/arch/build-arch.sh:10-25`

**Interfaces:**
- Produces: кэшируемый слой Gradle-конфигурации в `Dockerfile` и автоматическая подстановка `url='https://github.com/<owner>/<repo>'` в `PKGBUILD`.

- [ ] **Step 1: Разделить `COPY` в `packaging/docker/Dockerfile`**

Перед `COPY . .` скопировать отдельно `gradle/`, `gradlew`, `settings.gradle.kts`, `gradle.properties`, `build.gradle.kts` и `build-logic/` для кэширования обёртки и конфигурации.

- [ ] **Step 2: Добавить подстановку `url` в `packaging/arch/build-arch.sh`**

В `packaging/arch/build-arch.sh`:
```bash
owner="$(prop mimir.githubOwner)"
repo="$(prop mimir.githubRepo)"
pkgurl="https://github.com/${owner}/${repo}"
```
В `sed -i` для `PKGBUILD` добавить `-e "s|^url=.*|url=('${pkgurl}')|"`, после сборки восстановить `git checkout -- PKGBUILD` или проверить через `shellcheck`.

- [ ] **Step 3: Проверить скрипт через `shellcheck` и прогнать финальный `mise run ci:fast` + `context-init check`**

Run: `rtk shellcheck packaging/arch/build-arch.sh && rtk mise run ci:fast && rtk context-init check --compact`
Expected: `shellcheck` и `ci:fast` проходят без ошибок.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add packaging/
rtk oco -y
```
