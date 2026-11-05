# Спецификация: Комплексная стабилизация и оптимизация CI/Release, сборки Gradle и тестирования

**Дата:** 2026-11-06  
**Статус:** На согласовании  
**Связанные документы:** `docs/superpowers/specs/2026-10-23-mimir-design.md`, `docs/superpowers/specs/2026-11-03-cross-platform-auto-updates-design.md`, `docs/superpowers/specs/2026-11-04-monorepo-structure-and-ci-pipeline-design.md`, `docs/superpowers/specs/2026-11-05-ci-and-local-testing-design.md`

---

## 1. Замысел и цели (Intent & Why)

1. **Разблокировка выпуска релиза по всем платформам:** В текущей реализации слияние PR от Release Please (`chore(main): release X.Y.Z`) запускает `ci.yml` и `release-please.yml` одновременно. Через ~18 секунд `release-please.yml` создаёт тег и вызывает `release.yml`, который проверяет `check-runs` коммита. Так как `ci.yml` в этот момент ещё выполняется (`conclusion == null`), `release.yml` гарантированно падает с ошибкой `CI check-runs failed or incomplete`. Требуется устранить эту гонку и гарантировать выпуск всех релизных артефактов (Windows MSI, Android APK, Arch `.pkg.tar.zst`, Docker image, Obsidian plugin, Browser extension).
2. **Каноническое именование Android APK:** После переноса модуля из `:app-android` в `:apps:android` имя Gradle-проекта стало `android`, из-за чего AGP генерирует файлы `android-debug.apk` и `android-release-unsigned.apk` вместо имени продукта и версии из `gradle.properties`. Требуется формировать релизный APK с именем `mimir-<version>.apk` (подписанный) или `mimir-<version>-unsigned.apk` (неподписанный), полностью совместимым с `GitHubReleasesChannel`.
3. **Устранение дефектов и холостой работы в сборке Gradle:**
   - Починить `./gradlew buildHealth` (`com.autonomousapps.dependency-analysis`), который сейчас выдаёт пустой отчёт (`"projectCount": 0`).
   - Убрать холостую сборку production Wasm-бандла (Binaryen `wasm-opt` + Webpack) в библиотеке `:libs:ui`.
   - Исключить инвалидацию `:apps:daemon:jar` и повторный прогон `:apps:daemon:test` / `:apps:desktop:test` между шагами `ciJvmTests` и `build` в CI.
   - Отключить дублирующий прогон Robolectric-тестов на `Release`-варианте (`testReleaseUnitTest`) и `lintVitalAnalyzeRelease` в `:apps:android`.
   - Вынести дублирующиеся настройки JVM 26 (`options.release = 26`, `JvmTarget.JVM_26`, `group`, `version`) в convention-плагин `build-logic`.
4. **Стабилизация и полнота контура тестирования:**
   - Централизовать конфигурацию Karma (`ChromeHeadlessCI` с флагами `--no-sandbox --disable-dev-shm-usage --disable-gpu --headless=new` и таймаут Mocha 120 с) для всех KMP/JS модулей (`:libs:core`, `:libs:drive`, `:libs:client`, `:apps:browser-extension`).
   - Исключить тяжёлую генерацию PNG-скриншотов Skia из быстрого цикла `ciJvmTests` (оставив её для `mise run screenshots` / `-Pmimir.screenshots=true`) и добавить ненулевой код возврата при ошибках в `capture-screenshots.mjs`.
   - Проверять инвариант сборки без Android SDK на каждом прогоне `ciJvmTests` в Linux CI.
   - Покрыть unit-тестами модули `:apps:obsidian-plugin` (`jsTest`) и `:apps:web-ui` (`wasmJsTest`).

---

## 2. Цели, не-цели и анти-цели

- **Цели (Goals):**
  - Безотказное прохождение цепочки `Release Please PR merge` $\to$ `release-please.yml` $\to$ `release.yml` с публикацией всех 6 типов артефактов и `sha256sums.txt`.
  - Канонические имена APK: `mimir-<version>.apk` (signed), `mimir-<version>-unsigned.apk` (unsigned), `mimir-<version>-debug.apk` (debug).
  - Рабочий анализ зависимостей `./gradlew buildHealth` (`projectCount > 0`).
  - Сокращение времени сборки на `ubuntu-latest` за счёт отказа от двойной упаковки Wasm в `:libs:ui`, двойного прогона тестов `:apps:daemon`/`:apps:desktop`/`:apps:android` и кэширования Gradle в `arch`-джобе.
  - Единая конфигурация Karma Headless Chrome во всех JS-таргетах.
- **Не-цели (Non-Goals):**
  - Внедрение платного/внешнего Remote Build Cache (Develocity / S3).
  - Изменение бизнес-логики синхронизации, шифрования `RcloneCipher` или пользовательских экранов Compose.
- **Анти-цели (Anti-Goals):**
  - Отключение проверки статуса CI перед публикацией релиза в `release.yml`.
  - Дублирование литералов имени приложения или версии вне `gradle.properties`.
  - Подавление ошибок рендеринга скриншотов нулевым кодом возврата.

---

## 3. Инварианты Mimir

1. **Платформы:**
   - **Linux (`ubuntu-latest` / Arch):** в полном прогоне шаги `ciJvmTests` и `build --continue` выполняются с единым графом проектов (без смены `-Pmimir.bundleWebUi` и `ANDROID_HOME`), что исключает повторный прогон `:apps:daemon:test` и `:apps:desktop:test`. В ветках без Android (`js_or_web`-only, `is_release_pr`) сборка запускается с `env -u ANDROID_HOME -u ANDROID_SDK_ROOT`, ускоряя конфигурацию и проверяя инвариант работы без Android SDK. Джоба `arch` использует кэш Gradle и собирает `mimir-<version>-1-x86_64.pkg.tar.zst` со встроенным Web UI.
   - **Windows (`windows-latest`):** корректно включается в `os_matrix` при ручном `workflow_dispatch`; на релизных коммитах `chore(main): release ...` в `ci.yml` выполняет только быстрый `ciJvmTests` (~1.5 мин), не дублируя сборку `packageMsi`, которую параллельно выполняет `release.yml`.
   - **macOS (`macos-latest`):** сохраняет запуск по `schedule` и `workflow_dispatch` (`run_macos=true`).
   - **Android (`:apps:android`):** генерирует `mimir-<version>.apk` / `mimir-<version>-unsigned.apk` через задачу `packageReleaseApk`; `GitHubReleasesChannel` распознаёт подписанный `mimir-<version>.apk` для `TargetPlatform.ANDROID_ANY` и игнорирует `-unsigned.apk`.
   - **Docker и JS-клиенты (`:apps:daemon`, `:apps:web-ui`, `:apps:obsidian-plugin`, `:apps:browser-extension`):** `Dockerfile` и `build-arch.sh` явно передают `-Pmimir.bundleWebUi=true`; все JS-модули используют единый `ChromeHeadlessCI`.
2. **Дизайн:**
   - Конфигурация JVM 26 и Kotlin инкапсулируется в convention-плагине `mimir.jvm` в `build-logic` (паттерн Convention Plugin).
   - Именование APK следует правилу Single Source of Truth (`mimir.pacmanName` и `mimir.version` из `gradle.properties`).
3. **IO:**
   - На уровне сборочного IO исключена повторная оптимизация Binaryen (`wasm-opt` на 12 MiB Wasm) в `:libs:ui`, повторная запись тестовых JAR-архивов `:apps:daemon` и запись PNG-скриншотов при обычном прогоне unit-тестов `ciJvmTests`.
4. **UX:**
   - Пользователь, скачивающий APK из GitHub Releases вручную или через `ApkInstallApplier`, получает файл с понятным именем `mimir-<version>.apk` вместо технического `android-release.apk`.

---

## 4. Рассмотренные подходы (Approaches)

### 4.1. Координация `release-please.yml`, `ci.yml` и `release.yml`

| Подход | Описание | Компромиссы | Решение |
| :--- | :--- | :--- | :--- |
| **A. Выделенная gate-джоба `verify` в `release.yml` с ожиданием завершения `CI` + пропуск `packageMsi` в `ci.yml` на релизном коммите** | В `release.yml` перед `build`/`arch`/`docker` выполняется легковесная джоба `verify` (`ubuntu-latest`), которая проверяет `Tag matches mimir.version` и опрашивает `check-runs` коммита (ожидая завершения `in_progress`/`queued` с интервалом 15 с до 15 мин). В `ci.yml` на коммите `chore(main): release ...` пропускается дублирующая сборка `packageMsi` (выполняются только `ciJvmTests` на Windows и `build` на Linux). | Задерживает старт тяжелых сборочных джоб `release.yml` на ~6–8 минут (пока идёт `ci.yml`), но не держит запущенными платные `windows-latest` раннеры во время ожидания и исключает двойную сборку MSI. | **Выбран** |
| **B. Ожидание `check-runs` внутри матричных джоб `build` в `release.yml`** | Цикл `sleep` добавляется прямо внутрь `msi` (`windows-latest`) и `linux-bundles` (`ubuntu-latest`). | Два раннера (включая дорогой Windows) простаивают в `sleep` по 10 минут параллельно с `ci.yml`. | Отклонён |
| **C. Запуск `release-please.yml` по `workflow_run` после завершения `CI`** | `release-please.yml` триггерится не на `push`, а на `workflow_run: [CI] completed`. | Усложняет привязку к ветке `main` (если `CI` был запущен по `schedule` или `workflow_dispatch`) и задерживает даже обновление PR со списком изменений на 10 минут после каждого коммита. | Отклонён |

### 4.2. Формирование имени Android APK

| Подход | Описание | Компромиссы | Решение |
| :--- | :--- | :--- | :--- |
| **A. `base.archivesName` + задача `packageReleaseApk` в `apps/android/build.gradle.kts`** | Задаётся `base.archivesName.set("$pacmanName-$appVersion")` (даёт `mimir-<version>-debug.apk`). Задача `packageReleaseApk` копирует релизный APK из `outputs/apk/release/` в `build/distributions/mimir-<version>.apk` (если подписан) или `mimir-<version>-unsigned.apk` (если без ключа). | Вся логика именования инкапсулирована в Gradle и проверяется локально без bash-скриптов в YAML. | **Выбран** |
| **B. Переименование через `mv` в `.github/workflows/release.yml`** | В YAML-шаге `Mark unsigned APK` парсить `gradle.properties` и переименовывать `android-release*.apk`. | Логика именования размазана по CI-скрипту, локальная сборка продолжает выдавать `android-release.apk`. | Отклонён |

### 4.3. Конфигурация Karma Headless Chrome в KMP-модулях

| Подход | Описание | Компромиссы | Решение |
| :--- | :--- | :--- | :--- |
| **A. Генерация `karma.config.d/mimir-headless.js` и `useMocha` внутри convention-плагина** | В `build-logic` создаётся общий конфигуратор JS-браузера (применяется в `mimir.kmp-library` и `:apps:browser-extension`), который настраивает `useMocha { timeout = "120s" }` и подключает общий скрипт `karma.config.d` из корня проекта (`rootDir.resolve("gradle/karma.config.d")`) или генерирует его в `build/`. | Ноль дублирования файлов `headless.js` и `mocha-timeout.js` по модулям. | **Выбран** |
| **B. Копирование папки `karma.config.d/` во все 4 JS-модуля** | Положить `headless.js` и `mocha-timeout.js` в `libs/core`, `libs/drive`, `libs/client`, `apps/browser-extension`. | Нарушает DRY: 8 одинаковых JS-файлов в репозитории. | Отклонён |

---

## 5. Детальное техническое решение

### 5.1. Стабилизация `.github/workflows/release.yml` и `.github/workflows/ci.yml`

#### 5.1.1. Gate-джоба `verify` и сборка APK/Arch в `release.yml`
1. Добавить джобу `verify` (`runs-on: ubuntu-latest`, `timeout-minutes: 20`, `permissions: contents: read, checks: read`):
   - Выполняет `actions/checkout@v7` (`ref: ${{ env.SOURCE_REF }}`).
   - Проверяет совпадение тега с `mimir.version` (`if: ${{ inputs.dry_run != true }}`).
   - При `inputs.dry_run != true` выполняет ожидание и проверку `check-runs` коммита `$sha`:
     ```bash
     sha=$(git rev-parse HEAD)
     for attempt in $(seq 1 60); do
       runs=$(gh api "repos/${{ github.repository }}/commits/$sha/check-runs?per_page=100")
       detect_status=$(jq -r '[.check_runs[] | select(.name == "Detect Changed Modules")][0].status // "pending"' <<<"$runs")
       detect=$(jq -r '[.check_runs[] | select(.name == "Detect Changed Modules")][0].conclusion // "pending"' <<<"$runs")
       in_progress=$(jq '[.check_runs[] | select((.name | startswith("build (")) or .name == "Detect Changed Modules") | select(.status != "completed")] | length' <<<"$runs")
       total=$(jq '[.check_runs[] | select(.name | startswith("build ("))] | length' <<<"$runs")
       if [ "$detect_status" = "completed" ] && [ "$detect" != "success" ]; then
         break
       fi
       if [ "$detect" = "success" ] && [ "$total" -gt 0 ] && [ "$in_progress" -eq 0 ]; then
         break
       fi
       echo "Waiting for CI check-runs on $sha (attempt $attempt/60, in_progress=$in_progress, total=$total)..."
       sleep 15
     done
     failed=$(jq '[.check_runs[] | select((.name | startswith("build (")) or .name == "Detect Changed Modules") | select(.status != "completed" or (.conclusion != "success" and .conclusion != "skipped"))] | length' <<<"$runs")
     if [ "$total" -eq 0 ] || [ "$detect" != "success" ] || [ "$failed" -gt 0 ]; then
       echo "CI check-runs failed or incomplete for commit $sha"
       exit 1
     fi
     ```
2. Джобы `build`, `arch`, `docker` получают зависимость `needs: verify` и условие `if: ${{ !cancelled() && (needs.verify.result == 'success' || needs.verify.result == 'skipped') }}` (чтобы при `dry_run=true`, когда `verify` имеет статус `skipped`, сборочные джобы выполнялись; дублирующиеся шаги `Tag matches mimir.version` и `Verify CI status for release commit` из матрицы `build` удаляются). В цикле ожидания `check-runs` записи группируются по `.name` с выбором `max_by(.id)`, чтобы перезапуск CI после отмены/сбоя не блокировался старыми попытками.
3. В элементе матрицы `linux-bundles` (`release.yml`):
   - Заменить `:apps:android:assembleRelease` на `:apps:android:packageReleaseApk`.
   - Заменить путь артефакта `apps/android/build/outputs/apk/release/*.apk` на `apps/android/build/distributions/*.apk`.
   - Удалить шаг `Mark unsigned APK` (переименование выполняется задачей `packageReleaseApk`).
4. В джобе `arch` (`release.yml`) и `arch-dry-run` (`ci.yml`):
   - Добавить `actions/cache@v5` для `/root/.gradle/caches` и `/root/.gradle/wrapper` по ключу `arch-gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties', 'gradle/libs.versions.toml') }}`.
   - В `.github/scripts/build-arch.sh` и `packaging/docker/Dockerfile` явно передать `-Pmimir.bundleWebUi=true`.

#### 5.1.2. Исправление матрицы и шагов в `ci.yml`
1. **Матрица `detect-changes`:**
   - Передавать `COMMIT_SUBJECT: ${{ github.event.head_commit.message }}` в шаг `matrix`.
   - Если `EVENT_NAME == "workflow_dispatch"` (при `RUN_MACOS != "true"`): выставлять `package_msi=true` и `os_matrix=["ubuntu-latest","windows-latest"]`.
   - Если коммит в `main` является релизным (`[[ "$COMMIT_SUBJECT" == "chore(main): release "* ]]`): выставлять `package_msi=false` (так как релизный MSI собирается параллельно в `release.yml`), оставляя `os_matrix=["ubuntu-latest","windows-latest"]` для быстрого прогона `ciJvmTests`.
   - В шаге `Full build and packaging` учитывать `github.event_name == 'workflow_dispatch'` наравне с `schedule` для `CORE_OR_LIBS` и `DESKTOP`.
2. **Исключение двойного прогона тестов `:apps:daemon` и `:apps:desktop` на `ubuntu-latest`:**
   - Шаг `Fast JVM tests (ciJvmTests)` запускает `./gradlew ciJvmTests`.
   - Шаг `Full build and packaging` в ветке `else` запускает `./gradlew build --continue` **без** `-Pmimir.bundleWebUi=true`.
   - Так как `:apps:web-ui:build` сам вызывает `:apps:web-ui:wasmJsBrowserDistribution`, Wasm-клиент полностью собирается и проверяется, а `:apps:daemon:processResources` не меняет свои входы между шагами 1 и 2, благодаря чему `:apps:daemon:test` и `:apps:desktop:test` остаются `UP-TO-DATE`.
   - В ветках `is_release_pr == 'true'` и `js_or_web`-only команды Gradle запускаются с `env -u ANDROID_HOME -u ANDROID_SDK_ROOT`, исключая лишнюю конфигурацию AGP и проверяя инвариант работы без Android SDK.
3. **Синхронизация `actionlint`:**
   - Обновить версию `actionlint` в `ci.yml` до `1.7.12` (в соответствии с `mise.toml`) с проверкой SHA-256 архива релиза.

---

### 5.2. Именование и оптимизация Android-модуля (`apps/android/build.gradle.kts`)

1. В `apps/android/build.gradle.kts` задать базовое имя архивов из `gradle.properties`:
   ```kotlin
   val pacmanName = providers.gradleProperty("mimir.pacmanName").get()
   base {
       archivesName.set("$pacmanName-$appVersion")
   }
   ```
   При этом `assembleDebug` создаёт `apps/android/build/outputs/apk/debug/mimir-<version>-debug.apk` (обновить справочную команду в `.agents/skills/mimir-build-verify/SKILL.md` и `.claude/skills/mimir-build-verify/SKILL.md`).
2. Зарегистрировать задачу `packageReleaseApk`:
   ```kotlin
   val isSignedRelease = keystorePath != null
   val releaseApkFileName = if (isSignedRelease) "$pacmanName-$appVersion.apk" else "$pacmanName-$appVersion-unsigned.apk"

   val packageReleaseApk = tasks.register<Sync>("packageReleaseApk") {
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
3. Отключить дублирующий прогон unit-тестов на `Release`-варианте и дублирующий `lintVitalRelease`:
   ```kotlin
   android {
       lint {
           checkReleaseBuilds = false
       }
   }

   androidComponents {
       beforeVariants(selector().withBuildType("release")) { variantBuilder ->
           (variantBuilder as? com.android.build.api.variant.HasUnitTestBuilder)?.enableUnitTest = false
       }
   }
   ```

---

### 5.3. Починка `./gradlew buildHealth` (`com.autonomousapps.dependency-analysis`)

1. В `gradle/libs.versions.toml` добавить библиотеку плагина для `build-logic`:
   ```toml
   dependency-analysis-plugin = { module = "com.autonomousapps:dependency-analysis-gradle-plugin", version.ref = "dependencyAnalysis" }
   ```
2. В `build-logic/build.gradle.kts` добавить `implementation(libs.dependency.analysis.plugin)` — это помещает `dependency-analysis` в один classloader с `kotlin-gradle-plugin` и `com.android.tools.build:gradle`.
3. В корневом `build.gradle.kts` применять `id("com.autonomousapps.dependency-analysis")` к корневому проекту и JVM/Android подпроектам через `pluginManager.withPlugin`:
   ```kotlin
   subprojects {
       listOf("org.jetbrains.kotlin.jvm", "com.android.application").forEach { id ->
           pluginManager.withPlugin(id) {
               apply(plugin = "com.autonomousapps.dependency-analysis")
           }
       }
   }
   ```
   *(Ограничение DAGP 3.20.1: на KMP-модулях с внешними зависимостями `KmpSourceSetNameMatcher.getLowestCommonAncestor` вызывает `Graphs.lowestCommonAncestor` на пустом множестве `kmpSourceSetName` и падает с `UnsupportedOperationException: Empty collection can't be reduced`.)*
4. В результате `./gradlew buildHealth` анализирует JVM- и Android-подпроекты репозитория (`:apps:android`, `:apps:daemon`, `:apps:desktop`, `projectCount = 3 > 0`) и формирует непустой отчёт о графе зависимостей.

---

### 5.4. Оптимизация сборки Gradle (`:libs:ui`, `:apps:daemon`, `build-logic`, `gradle.properties`)

1. **Удаление холостой сборки Wasm executable в `:libs:ui`:**
   - В `libs/ui/build.gradle.kts` удалить строку `binaries.executable()` из блока `wasmJs { browser() }`.
   - Удалить файл `libs/ui/webpack.config.d/performance.js`.
2. **Статическая тестовая страница для `WebUiServedTest` в `:apps:daemon`:**
   - В `apps/daemon/build.gradle.kts` убрать условие `else { tasks.test { filter { excludeTestsMatching("*WebUiServedTest") } } }`.
   - Добавить в `apps/daemon/build.gradle.kts`:
     ```kotlin
     tasks.processTestResources {
         from(rootProject.file("apps/web-ui/src/wasmJsMain/resources")) {
             include("index.html")
             into("static")
         }
     }
     ```
   - Теперь `WebUiServedTest` выполняется всегда (включая `mise run test:fast` / `ciJvmTests`) за миллисекунды без компиляции Wasm, а в дистрибутивах (`installDist`, `distTar`, `distZip`, `package*`, `-Pmimir.bundleWebUi=true`) в `main` ресурсы по-прежнему упаковывается полный бандл `:apps:web-ui:wasmJsBrowserDistribution`.
3. **Convention-плагин `mimir.jvm` в `build-logic`:**
   - Создать `build-logic/src/main/kotlin/mimir.jvm.gradle.kts`, задающий `group`, `version`, `JavaCompile.options.release = 26` и `KotlinCompile.compilerOptions.jvmTarget = JvmTarget.JVM_26`.
   - Подключить его в `apps/desktop/build.gradle.kts` и `apps/daemon/build.gradle.kts`, удалив дублирующиеся блоки.
4. **Параллельное выполнение в `gradle.properties`:**
   - Добавить `org.gradle.parallel=true` в `gradle.properties`.

---

### 5.5. Унификация тестирования (Karma, скриншоты, unit-тесты клиентов)

1. **Единая конфигурация Karma (`ChromeHeadlessCI` + Mocha timeout):**
   - Переместить `apps/browser-extension/karma.config.d/headless.js` и `mocha-timeout.js` в общий каталог `gradle/karma.config.d/`.
   - Удалить дубликаты `apps/browser-extension/karma.config.d/` и `libs/core/karma.config.d/`.
   - В `build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts`, `apps/browser-extension/build.gradle.kts` и `apps/obsidian-plugin/build.gradle.kts` в блоке `js { browser { testTask { ... } } }` настроить:
     ```kotlin
     useKarma {
         useChromeHeadless()
         useConfigDirectory(rootProject.file("gradle/karma.config.d"))
     }
     ```
2. **Изоляция скриншотных тестов от быстрого прогона `ciJvmTests` и проверка кода возврата:**
   - В `libs/ui/build.gradle.kts` для задачи `desktopTest`: по умолчанию исключать `*ScreenshotTest` (`filter { excludeTestsMatching("*ScreenshotTest") }`), если не передано свойство `-Pmimir.screenshots=true` и в CLI явно не запрошен `--tests *Screenshot*`.
   - В `mise.toml` задача `screenshots` передаёт `-Pmimir.screenshots=true`:
     `./gradlew :libs:ui:desktopTest -Pmimir.screenshots=true --rerun --tests 'dev.mimir.ui.*Screenshot*' ...`
   - В `apps/browser-extension/scripts/capture-screenshots.mjs`: добавить счётчик ошибок `let failed = false;` в блок `catch` и `if (failed) process.exit(1);` в конце скрипта.
3. **Unit-тесты для `:apps:obsidian-plugin` и проверка `:apps:web-ui`:**
   - В `apps/obsidian-plugin/build.gradle.kts`: настроить `js { browser { testTask { ... } } }` (с общим `gradle/karma.config.d` и `kotlin("test")` в `jsTest`), в `webpack.config.d/obsidian.js` для `config.mode !== "production"` подставлять минимальную заглушку `obsidian` (`Plugin`, `Notice`), и добавить unit-тест `apps/obsidian-plugin/src/jsTest/kotlin/dev/mimir/obsidian/ObsidianPluginTest.kt`.
   - Для `:apps:web-ui` (тонкая точка входа из 1 строки `ComposeViewport { MimirApp(StubSyncEngine()) }`): собственные `wasmJsNodeTest` и `wasmJsBrowserTest` не подключаются, так как `skiko.mjs` в Compose Multiplatform 1.12 требует браузерного WebGL/fetch при загрузке модуля (падает в Node.js), а Karma не поддерживает ESM `import.meta` в сгенерированном `.mjs` загрузчике Kotlin/Wasm. Логика покрывается в `:libs:core:wasmJsNodeTest`, `:libs:client:wasmJsNodeTest` и `:libs:ui:desktopTest`, сборка бандла проверяется `:apps:web-ui:wasmJsBrowserDistribution`, а отдача HTML — в `:apps:daemon:test` (`WebUiServedTest`).

---

## 6. Верификация

1. **Быстрый контур (без Android SDK):** `rtk env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew ciJvmTests` — проходит успешно, в логе `Android SDK not found: skipping Android targets`, `WebUiServedTest` выполняется и проходит, скриншотные тесты пропущены.
2. **Полная сборка и тесты всех платформ:** `rtk mise run build` — проходит успешно; `:libs:ui` не запускает `wasmJsBrowserProductionWebpack`; `:apps:android` не запускает `testReleaseUnitTest`; все 4 JS-модуля (`:libs:core`, `:libs:drive`, `:libs:client`, `:apps:browser-extension`) и `:apps:obsidian-plugin` проходят Karma-тесты на `ChromeHeadlessCI`; `:apps:web-ui` проходит `wasmJsNodeTest`.
3. **Упаковка Android APK:** `rtk ./gradlew :apps:android:packageReleaseApk` — создаёт `apps/android/build/distributions/mimir-<version>-unsigned.apk` (при отсутствии ключей подписи) или `mimir-<version>.apk` (при наличии ключей).
4. **Анализ зависимостей:** `rtk ./gradlew buildHealth` — успешно завершается, в `build/reports/dependency-analysis/build-health-report.json` значение `projectCount > 0`.
5. **Скриншоты и линтеры:** `rtk mise run screenshots` и `rtk mise run ci:fast` — проходят с кодом `0`.
