# Mimir — руководство для агентов

Mimir — клиент синхронизации Obsidian-хранилища с шифрованием поверх Google Drive. Спека: `docs/superpowers/specs/2026-10-23-mimir-design.md`, план скелета: `docs/superpowers/plans/2026-10-23-mimir-skeleton.md`. Спека — первичный источник; при конфликте с планом прав спека.

## Среда

- **Машина разработки — Arch Linux** (bash). На Windows-ПК пользователя ничего не устанавливать и не запускать (ни gradle, ни mise, ни JDK). Windows-специфичное (MSI) проверяется только в CI. Без явного разрешения пользователя сессия на Windows = «только писать файлы».
- Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8. Откат компонента на JDK 25 допустим только с записью причины в README («Версии»).
- Запуск через `mise run <задача>` (см. `mise.toml`); у каждой задачи есть `run_windows`.

## Инварианты Mimir (в каждой задаче, без напоминаний)

Хост разработки (Arch + niri) — **одна из целей, а не эталон**. Проект большой и долгоживущий: решения принимаются по паттернам, а не «как быстрее заработает здесь».

1. **Платформы** — Linux (любой DE/WM, не только niri), Windows, macOS, Android, Docker-демон без дисплея, JS-клиенты (Obsidian, расширение, web-ui). Диалоги выбора файлов и папок на всех платформах открывают **стандартный нативный проводник ОС** (Windows Explorer, macOS Finder, Linux Portal/zenity/kdialog, Android SAF DocumentsUI), а не встроенные Swing-окна. → скилл `mimir-cross-platform`.
2. **Дизайн** — новый код встраивается в существующие паттерны (порты/адаптеры, декоратор, реестр, стратегия, план→исполнение, UDF). → скилл `mimir-design-patterns`.
3. **IO** — Drive медленный и квотируемый, vault большой: стоимость в вызовах и прочитанных байтах, асинхронность, потоки вместо массивов. → скилл `mimir-sync-io`.
4. **UX** — любой экран: все состояния, современный вид по токенам темы, человеческие тексты, проверка глазами. → скилл `mimir-ui-ux`.

Каждая спека и каждый план содержат раздел **«Инварианты Mimir»** из четырёх пунктов: *Платформы* (что происходит на каждой цели или «неприменимо: причина»), *Дизайн* (паттерн + эталонный класс), *IO* (вызовы Drive и чтения на N файлов, где параллелизм, на каком диспетчере), *UX* (сценарий и состояния экрана). Отчёт о выполнении задачи повторяет эти четыре пункта с фактическим результатом.

## Структура

| Модуль | Назначение |
|---|---|
| `build-logic` | convention-плагины: `mimir.settings`, `mimir.android-support`, `mimir.kmp-library`; `AndroidSdk` |
| `libs/core` | чистый Kotlin (jvm, js, wasmJs, android): порты, шифрование, локальная ФС, движок синхронизации, фейки для тестов |
| `libs/drive` | адаптер Google Drive: HTTP, OAuth, путь→id, загрузка |
| `libs/client` | контракт `DaemonService`, HTTP-клиент `HttpDaemonClient`, общие DTO демона и автообновлений (jvm, js, wasmJs, android) |
| `libs/runtime` | общая логика демона и Android (KMP jvm/android): движок, вход в Google, база состояния, планировщик |
| `libs/ui` | общий Compose-интерфейс (desktop + wasmJs, Android при SDK) |
| `apps/daemon` | единый бэкенд: сборка движка, HTTP API, планировщик, вход в Google (встраивается в desktop или работает отдельно/в Docker) |
| `apps/desktop` | окно, трей, выбор папки, браузер; встроенный или удалённый демон |
| `apps/android`, `apps/web-ui`, `apps/obsidian-plugin`, `apps/browser-extension` | клиентские точки входа (Android APK, Wasm-клиент демона, плагин Obsidian, автономное Chromium MV3-расширение) |
| `packaging/`, `.github/` | Arch PKGBUILD, Docker, CI (ubuntu/windows/macos) и релиз |

Зависимости идут только вниз: точки входа (`apps/*`) → `libs/ui` / `libs/runtime` → `libs/client` / `libs/drive` → `libs/core`. `libs/core` не знает ничего о платформе.

## Карта кода (где искать)

Пакеты — `dev.mimir.<module>.<area>`; тест класса `X` — `XTest.kt` в соседнем `*Test`-наборе. Сначала смотреть сюда, потом искать (`rg "class X"`).

| Что | Где |
|---|---|
| Контракты (порты) | `libs/core/…/core/api/`: `RemoteStorage`, `LocalVault`, `AuthProvider`, `SecretStore`, `NetworkState`, `RemoteStorageProvider`/`Registry` |
| Шифрование | `libs/core/…/crypto/`: `RcloneCipher`, `EncryptedRemoteStorage` (декоратор), примитивы |
| Локальная ФС | `libs/core/…/fs/`: `FileSystemLocalVault`, `VaultWatcher` (+ `jvmMain/fs` NIO, `androidMain`, `jsMain`), `VaultPathFilter`, `EchoFilter` |
| Синхронизация | `libs/core/…/sync/`: решение — `SyncPlanBuilder` → `SyncPlan`; исполнение — `DefaultSyncEngine`; `ConflictResolver`, `SyncIndex` + `SyncStateStore`, `SyncProgress`, `RemoteExceptions` |
| Правила синхронизации | `libs/core/…/rules/` (`SyncRules`, `GlobMatcher`) |
| Фейки для тестов | `libs/core/…/testing/`: `InMemoryRemoteStorage`, `InMemoryLocalVault`; UI — `libs/ui/model/FakeMimirUiModel` |
| Google Drive | `libs/drive/`: порт — `GoogleDriveStorage`; токены, retry, ошибки сети — `client/DriveHttpClient`; OAuth/PKCE — `auth/`; путь→id и кэш — `path/DrivePathResolver`; загрузка (resumable) — `upload/DriveUploader` |
| Клиент демона | `libs/client/`: контракт — `DaemonService`; HTTP-клиент — `HttpDaemonClient`; DTO — `DaemonDto`, `UpdateDto` |
| Runtime | `libs/runtime/`: фасад — `MimirRuntime`; сборка движка — `EngineAssembler`; вход — `login/`; настройки — `JsonRuntimeSettingsStore` |
| Демон | composition root — `DaemonRuntime`; API — `Routes` + `ProgressDto`; конфиг, env и каталоги ОС — `DaemonConfig`; расписание — `SyncScheduler`; вход через браузер — `BrowserLoginReceiver`; индекс — `JsonSyncStateStore`, атомарная запись — `AtomicFiles.kt` |
| UI и дизайн-система | контракт — `libs/ui/model/MimirUiModel`; экраны — `libs/ui/screens/`; крафтовые компоненты — `libs/ui/components/MimirComponents.kt`; токен-константы — `libs/ui/MimirTokens.kt`; темы — `MimirTheme`, `ObsidianStyle`; тексты — `SyncTexts`; сервисы (CompositionLocal) — `libs/ui/picker/`, `libs/ui/platform/` |
| Дизайн-токены (Single Source of Truth) | `design/tokens.json`; генератор — `design/generate-tokens.mjs` (`mise run tokens:generate` / `tokens:check`); артефакты — `libs/ui/MimirTokens.kt`, `apps/browser-extension/.../tokens.css` |
| Desktop | composition root — `apps/desktop/Main.kt`; встроенный/удалённый демон — `DaemonServiceFactory`; трей — `tray/`; выбор папки — `picker/`; браузер — `SystemBrowserOpener` |
| Сборка | convention-плагины — `build-logic/src/main/kotlin/`; версии — `gradle/libs.versions.toml`; идентификаторы — `gradle.properties` |

## Правила

- **Идентификаторы** (`mimir.appName`, `mimir.version`, `mimir.group`, `mimir.appId`, `mimir.obsidianPluginId`, `mimir.pacmanName`, `mimir.dockerImage`, `mimir.msiUpgradeUuid`, `mimir.apiDefaultPort`, `mimir.extensionPublicKey`, `mimir.githubOwner`, `mimir.githubRepo`) живут только в `gradle.properties`. В код и манифесты попадают через BuildConfig / `processResources` / генерацию, литералами не дублируются.
- Пакет: `dev.mimir.<module>`. Версии зависимостей — только в `gradle/libs.versions.toml`.
- Gradle: Kotlin DSL, configuration cache и build cache включены — не ломать их. Контроль графа зависимостей (`api` vs `implementation`, неиспользуемые зависимости) осуществляется плагином `com.autonomousapps.dependency-analysis` (`./gradlew buildHealth`, режим `severity("warn")`).
- **Android SDK необязателен**: без него `mise run build` обязан проходить, пропуская Android-таргеты с предупреждением `Android SDK not found: skipping Android targets`. Не добавлять безусловных ссылок на `:apps:android` и Android-плагины.
- **Секреты и окружение Gradle Daemon**: секреты живут только в `mise.local.toml` (в `.gitignore`) и GitHub Secrets. Сборка и тесты проходят без единого секрета. Задачи `JavaExec` (`:apps:desktop:run`, `:apps:daemon:run`) обязаны явно синхронизировать переменные `MIMIR_*` через `providers.environmentVariablesPrefixedBy("MIMIR_")`, очищая унаследованные ключи демона. При кардинальной смене переменных в `mise.local.toml` выполнять `./gradlew --stop`.
- **Docker-образ демона** строится на glibc-базе (`eclipse-temurin:27-jdk` / `27-jre`), а не на alpine: Kotlin/JS скачивает Node под glibc.
- **Android**: нужны AGP ≥ 9.4 и compileSdk 37 (иначе jlink `JdkImageTransform` падает на JDK 27, а Compose 1.12 требует compileSdk 37).
- Шифрование реализовано (`RcloneCipher`, совместим с rclone crypt, чистый Kotlin в `libs/core`; `expect/actual` в `libs/core` — только источник случайности и проверка символьных ссылок). Синхронизация, Google Drive, OAuth и клиентские точки входа реализованы; заглушкой (`NotImplementedError` с осмысленным сообщением) остаётся только `StubSyncEngine`. Пароль в `RcloneCipher` передаётся в открытом виде; golden-векторы `RcloneVectors.kt` генерирует `libs/core/scripts/gen-rclone-vectors.sh` (нужен rclone), обычные сборка и тесты rclone не требуют. **Важно:** `rclone copyto` генерирует случайный нонс для каждого файла, поэтому `FileVector` в `RcloneVectors.kt` меняется при каждом перезапуске скрипта; детерминированы только `NameVector`. При проверке работоспособности скрипта временные изменения `RcloneVectors.kt` сбрасываются (`git checkout`).
- **Google OAuth-клиенты**: две пары — `MIMIR_GOOGLE_DESKTOP_CLIENT_ID/SECRET` (вход через браузер) и `MIMIR_GOOGLE_DEVICE_CLIENT_ID/SECRET` (Device Code); старые `MIMIR_GOOGLE_CLIENT_*` — алиас Device-пары. Вшитые значения (`MIMIR_BUILD_GOOGLE_*`) живут только в BuildConfig `apps/daemon` и `apps/android` (Desktop-пара допустима в BuildConfig `apps/android` для fallback-входа через браузер; не в `libs/core`: он попадает в JS-бандлы), в Docker-образ не вшиваются — Docker-демон настраивается переменными окружения. В локальной разработке `apps/android/build.gradle.kts` читает `MIMIR_BUILD_GOOGLE_DESKTOP_*` с fallback на `MIMIR_GOOGLE_DESKTOP_*` из `mise.local.toml`. Для нативного системного входа на Android (GIS) в Google Cloud Console регистрируется OAuth-клиент типа Android с пакетом `dev.mimir.app` и SHA-1 ключа (debug: `84:05:F0:7B:51:7F:DA:C0:F7:7B:9D:B3:27:F6:37:97:C1:A4:56:3C`); при его отсутствии активируется fallback в браузер через Desktop-клиент.
- **Linux-лаунчеры** (`run` в `apps/desktop/build.gradle.kts`, `packaging/arch/mimir.sh`) выставляют `_JAVA_AWT_WM_NONREPARENTING=1` — иначе под niri окно рисуется только в левом верхнем углу.
- **Десктопное оформление**: главное окно сохраняет нативную рамку ОС (`undecorated = false`), стилизованную в цвета `MimirTokens` через `PlatformWindowStyler` (JDK 27 FFM `DwmSetWindowAttribute` на Windows, прозрачный `fullWindowContent` на macOS, нативное поведение тайлинга на Linux); трей — Compose-поповер (`Window.Type.POPUP` для защиты от тайлинга на Linux) с сохранением D-Bus меню для Linux-панелей.
- `SyncStatus` включает `Offline` (нет сети); прогресс — `SyncEngine.progress`. Ошибка одного файла не прерывает синхронизацию: она попадает в проблемы и повторяется в следующем прогоне.
- Защита от удаления: при пустом удалённом списке и непустом индексе движок прерывает прогон (`SyncAbortedException`), ничего не трогая; каждый `DeleteLocal` перед выполнением перепроверяется через `stat` (файл найден или проверка не удалась — локальный файл не удаляется). `GoogleDriveStorage.listAll` сбрасывает кэши путей, чтобы `stat` не был устаревшим. `GoogleDriveStorage.delete` переносит файл в корзину Drive, а не стирает его.
- Параллельность синхронизации: `DefaultSyncEngine(parallelism = 4, dispatcher = Dispatchers.IO)` из `DaemonRuntime`; настройка `syncParallelism` / `MIMIR_SYNC_PARALLELISM` (1..16). `Dispatchers.IO` передаётся только из `apps/daemon`, в `libs/core`/`libs/drive` commonMain диспетчер — параметр.
- Vault не ходит по символьным ссылкам: их пути отдаёт `LocalVault.hiddenPaths()`, и план синхронизации пропускает всё под ними, как исключения (иначе файл за ссылкой выглядит удалённым и стирается в Drive).
- Строка статуса до подключения — ровно `Mimir: not connected` (`SyncStatus.label()`).
- Локальный HTTP API: `127.0.0.1:47821`, адрес/порт настраиваются; занятый порт → сообщение `Port <port> on <bind> is already in use`, код 2, без стектрейса.
- Часы — `kotlin.time.Clock`; отдельного порта `Clock` нет.
- **Kotlinx Serialization DTO и обратная совместимость**: При добавлении опциональных полей со значением по умолчанию (особенно в разделяемые DTO ответов ошибок вроде `DaemonErrorBody`) обязательно использовать аннотацию `@EncodeDefault(EncodeDefault.Mode.NEVER)`. Ktor по умолчанию использует `explicitNulls = true`, и без аннотации поле `val code: String? = null` сериализуется как `"code": null`, что ломает точные строковые проверки в существующих тестах и сторонних клиентах.
- Платформы: Linux — Arch + системная Java (`java-runtime>=27`); Windows — MSI per-user с вшитой JRE, ярлыками на рабочем столе и в меню Пуск, и диалогом выбора папки; Android — APK вне Play, `MANAGE_EXTERNAL_STORAGE`; браузер — только Chromium, MV3; плагин Obsidian не `isDesktopOnly` и не использует ничего, кроме модуля `obsidian`.
- **Браузерное расширение (`:apps:browser-extension`, Chromium MV3)**: работает автономно (без локального демона) поверх `:libs:core` и `:libs:drive` (Kotlin/JS). Основной интерфейс — `chrome.sidePanel` (пошаговый переход Список → Заметка) и полноэкранная двухколоночная вкладка (`popup.html?mode=tab`) с fallback в Popup; встраивание в DOM `drive.google.com` не используется. Деривированный 80-байтный ключ `RcloneCipher` живёт только в RAM (`chrome.storage.session`), а в `chrome.storage.local` кэшируются исключительно зашифрованные `RemoteEntry` и `.canary_hash`. Для стабильного extension ID в `gradle.properties` задаётся `mimir.extensionPublicKey`; вход — PKCE через Desktop-клиент с loopback-redirect `http://127.0.0.1:47999/callback` (страница входа открывается во вкладке, переход перехватывается `chrome.tabs.onUpdated`, регистрировать redirect в Google Cloud не нужно) и резервный Device Code Flow; корневая папка Drive берётся из `MIMIR_ROOT_FOLDER` при сборке.
- **Тестирование корутин и фоновых компонентов**: При тестировании классов с долгоживущими корутинами или периодическими тикерами (`MimirRuntime`, `SyncScheduler`, бесконечные `flow.collect`) категорически запрещено вызывать `testScheduler.advanceUntilIdle()` в `runTest` — это приводит к бесконечному циклу прокрутки виртуального времени. Использовать явный сдвиг на фиксированное время (`advanceTimeBy`), изолированные фейки контрактов (`SyncRunner`) вместо полного рантайма или завершать `scope.cancel()` перед окончанием теста.
- **Kotlin/JS исключения и UTF-8**: В JS-рантайме нативные JavaScript ошибки (в частности, `TypeError: URI malformed` при невалидной UTF-8 последовательности в `decodeToString(throwOnInvalidSequence = true)` или `Buffer.readString()`) наследуются от `Throwable` и нативного `Error`, но НЕ являются подклассами `kotlin.Exception`. Блоки `catch (e: Exception)` пропускают их, приводя к необработанным крашам тестов. В коде шифрования, валидации канареек и чтения внешних потоков перехватывать `Throwable` с обязательным пробросом `CancellationException`.
- **Compose Multiplatform UI Preview**: `@Preview` в `commonMain` модуля `:libs:ui` — `androidx.compose.ui.tooling.preview.Preview` из артефакта `org.jetbrains.compose.ui:ui-tooling-preview` (`libs.compose.ui.tooling.preview`); варианты wasmJs и android в нём есть с Compose 1.12. DSL `compose.components.uiToolingPreview` и `compose.uiTooling` объявлены устаревшими.
- **Выборочный запуск CI и кроссплатформенная матрица**: В `.github/workflows/ci.yml` джоб `detect-changes` (`dorny/paths-filter@v4`) вычисляет затронутые подсистемы и формирует динамическую матрицу ОС (`os_matrix`). Изменения в `libs/**`, `build-logic/**`, `apps/desktop/**` или `apps/daemon/**` запускают матрицу `ubuntu-latest` + `windows-latest` (`macos-latest` исключён из блокирующего PR-чека из-за очередей раннеров и вызывается опционально через `workflow_dispatch` с `run_macos=true`). Изменения только в `apps/android/**` или `apps/obsidian-plugin/**` пропускают Windows; релизные PR (`release-please--*`) запускают только быструю проверку версии на `ubuntu-latest` (`env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew -p build-logic test && ./gradlew :apps:desktop:help`) без пробуждения Windows; `workflow_dispatch` запускает `ubuntu-latest` + `windows-latest`; на коммите слияния релизного PR (`chore(main): release *`) `package_msi=false` (MSI собирает `release.yml`). Во всех джобах первым идёт быстрый шаг `./gradlew ciJvmTests` (все JVM/Desktop-тесты и `build-logic` без Wasm/JS/Android). Затем на `ubuntu-latest` выполняется `./gradlew build --continue` (KMP/JS/Wasm/Android тесты, без `-Pmimir.bundleWebUi`, чтобы не инвалидировать `:apps:daemon:test`), а на `windows-latest` — `packageMsi -Pmimir.bundleWebUi=true` только при `package_msi=true` (push, `workflow_dispatch` или изменения упаковки). Ночной `schedule` (03:00 UTC) запускает полную матрицу с macOS и dry-run джобы Arch и Docker. Web UI попадает в `static/` демона только при `-Pmimir.bundleWebUi=true` или задачах упаковки (`package*`, `installDist`, `distTar`, `distZip`); `build-arch.sh` и `Dockerfile` передают флаг явно. Для `WebUiServedTest` `processTestResources` в `:apps:daemon` кладёт `apps/web-ui/src/wasmJsMain/resources/index.html` в `static/`, поэтому тест идёт в `ciJvmTests` без Wasm-сборки. `*ScreenshotTest` в `:libs:ui` выполняются только с `-Pmimir.screenshots=true` (`mise run screenshots`). Convention-плагин `mimir.jvm` задаёт JVM 26 для `apps/desktop` и `apps/daemon`; `com.autonomousapps.dependency-analysis` применяется к подпроектам из корня.
- **Релиз и Android APK**: `release.yml` начинается с gate-джобы `verify` (тег = `mimir.version`, ожидание `check-runs` коммита до 60×15 с с дедупликацией по `max_by(.id)`; `build`/`arch`/`docker` имеют `needs: verify` и `if: !cancelled() && (needs.verify.result == 'success' || needs.verify.result == 'skipped')` для поддержки `dry_run=true`). Android собирается задачей `:apps:android:packageReleaseApk`: `apps/android/build/distributions/mimir-<версия>.apk` при полном наборе секретов `MIMIR_KEYSTORE_PATH/PASSWORD` и `MIMIR_KEY_ALIAS/PASSWORD`, иначе `mimir-<версия>-unsigned.apk` (суффикс нужен `GitHubReleasesChannel.classify`); `lintVitalRelease` и `testReleaseUnitTest` отключены (`variantBuilder.enableUnitTest = false`). Debug APK — `apps/android/build/outputs/apk/debug/mimir-*-debug.apk`. Точка входа `:apps:web-ui` не имеет собственных `wasmJsNodeTest`/`wasmJsBrowserTest` (Skiko требует браузерного WebGL в `skiko.mjs`, а Karma не поддерживает `import.meta` в Wasm ESM): логика покрыта в `:libs:core:wasmJsNodeTest`, `:libs:client:wasmJsNodeTest`, `:libs:ui:desktopTest`, сборка проверяется `:apps:web-ui:wasmJsBrowserDistribution`, раздача — `WebUiServedTest` в `:apps:daemon:test`. Локально: `mise run test:fast` (JVM-тесты) и `mise run ci:fast` (токены + `jactionlint` + `ciJvmTests`) перед push.
- **CI Runners, Java и Symlinks на Windows**: На раннерах `windows-latest` GitHub Actions системная переменная `JAVA_HOME` по умолчанию выставлена на Java 17; плагин `mise-action` не переопределяет её — шаги сборки обязаны нормализовать `java_home=$(mise where java | tr '\\' '/')` и экспортировать в `$GITHUB_ENV` и `$GITHUB_PATH`. Для корректной работы Kotlin/JS (Yarn Workspaces symlinks в `node_modules`) и глубокой вложенности пакетов на Windows-раннере обязательно включать Developer Mode (`AllowDevelopmentWithoutDevLicense = 1`), `LongPathsEnabled = 1` и `git config --global core.symlinks true / core.longpaths true` перед шагом сборки.
- **Windows MSI обновления и лаунчер**: Инсталлятор Windows (`jpackage` / WiX) выполняет автоматический `MajorUpgrade` только при повышении версии (`0.2.1 > 0.2.0`). Переустановка нового билда с идентичной версией блокируется Windows Installer (ошибка 1638: «Уже установлена другая версия этого продукта») и при локальном тестировании требует предварительного удаления старой версии. В конфиг `<app>.cfg` jpackage jvm-лаунчера на Windows добавляется `win.norestart=true`, исключающий дублирование процесса-обёртки в Диспетчере задач.
- **Karma ChromeHeadless и таймауты в CI**: Тесты браузерного расширения (`:apps:browser-extension:jsBrowserTest`) в Linux-контейнерах CI требуют флагов `--no-sandbox --disable-setuid-sandbox --disable-dev-shm-usage --disable-gpu --headless=new` (`headless.js`), а из-за вычислений scrypt в JS — таймаута mocha не менее 120 с (общий каталог `gradle/karma.config.d/`: `headless.js`, `mocha-timeout.js`, подключается через `useConfigDirectory` в `mimir.kmp-library`, `:apps:browser-extension` и `:apps:obsidian-plugin`).
- **Скачивание и публикация релизных артефактов**: `actions/download-artifact@v4` обязан вызывать скачивание строго по именам артефактов (`name: msi`, `name: apk`, etc.), исключая захват служебных файлов Docker Buildx. Перед формированием `sha256sums.txt` файлы из поддиректорий `dist` должны сплющиваться в корень каталога, а пустые директории удаляться.
- **Глубокие ссылки (URI Scheme) и фокус на Windows**: `appResourcesRootDir` в Compose Desktop копирует файлы исключительно в runtime-каталог `$APPDIR\resources`, а не передаёт их в WiX/jpackage; WiX не компилирует лежащие там файлы `overrides.wxi/wxs`. Регистрация кастомных URI-схем (`mimir://`) на Windows должна выполняться приложением при старте (`WindowsProtocolRegistrar`) в ветке текущего пользователя `HKCU\Software\Classes\<scheme>` (не требует прав администратора, чинит работу как после MSI, так и при portable/dev запусках). Для предотвращения сбоев экранирования командной строки `reg.exe` использовать атомарный временный файл с `reg import` в UTF-8. Для работы нативного Win32 FFM (`AllowSetForegroundWindow`, `Linker.nativeLinker`) в безымянном модуле `apps/desktop` обязателен JVM-флаг `--enable-native-access=ALL-UNNAMED` в `compose.desktop.application.jvmArgs`. Для обхода Windows Focus Stealing Prevention при IPC-активации окна из браузера вызывать toggling `isAlwaysOnTop = true; isAlwaysOnTop = false` перед `toFront()`.
- **Kotlin JS & Wasm Yarn Lockfile Configuration**: В Kotlin Multiplatform 2.x расширение Wasm Yarn находится в пакете `org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension` (а не `js.yarn`), а JS Yarn — в `org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension`. Для переопределения каталога лок-файлов используется Provider API: `lockFileDirectoryProperty.set(...)` (свойство `lockFileDirectory` устарело). Защита от тихой регенерации: при ошибке в пути или отсутствии настройки одного из таргетов Gradle молча воссоздает дефолтный `kotlin-js-store/` в корне без сбоя сборки. При любых изменениях путей yarn lock-файлов обязательна проверка через `./gradlew kotlinNpmInstall kotlinWasmNpmInstall` с подтверждением отсутствия каталога `kotlin-js-store/` и проверкой `git status` (чистый 100% rename без изменений содержимого). В `.github/workflows/ci.yml` фильтр `js_or_web` обязан указывать на актуальный каталог (`gradle/yarn/**`).
- **Жизненный цикл синхронизации и фоновая сеть на Android (`:apps:android`, `:libs:runtime`)**: На Android 15/16 через 5 с после `MainActivity.onStop` системный `NetworkPolicyManager` выставляет для `UID` блокировку `blocked=APP_BACKGROUND`, принудительно разрывая сокеты обычного процесса (`EOFException` → `RemoteUnavailableException` → ложный `SyncStatus.Offline`). Поэтому: (1) в `AndroidRuntimeFactory` рантайм создаётся с `initialInteractive = false` (при холодном старте процесса из `WorkManager` без `MainActivity` `ProcessLifecycleOwner` не вызывает `onStart`/`onStop`, и фоновые таймеры `SyncScheduler`/`UpdateScheduler` запускаться не должны); (2) в `onStart` вызывается только `rt.initialize()` + `rt.setInteractive(true)` внутри `try/catch` без дублирующего `rt.syncOnce()` (иначе возникает гонка с `getLatestChangeToken()` в `SyncScheduler.tick()` и двойной обход хранилища, а ошибка сети роняет процесс из-за `rethrow = true` в `runNow()`); (3) в `onStop` вызов `rt.setInteractive(false)` останавливает планировщик с `cancelRunner = true` (кооперативная отмена со сбросом `index.json` и статусом `Idle`), а при прерванном прогоне или `pendingLocalChanges` передаёт докачку в `workScheduler.scheduleExpedited()`; (4) задачи `WorkManager` (`schedulePeriodic`, `scheduleVaultTrigger`) регистрируются реактивно по `runtime.vaultPathFlow`; (5) при входе через Google Identity Services со скоупом `drive.file` `toGoogleSignInAccount()?.email` равен `null`, поэтому email из `fetchAccount` обязательно сохраняется в `PREF_GIS_ACCOUNT` с fallback на `runtime.accountEmail` (для работы `authorizeSilently()` в `WorkManager`), а `verifyOrInitCanary` перехватывает `RemoteUnavailableException` наряду с `IOException` для офлайн-проверки `.canary_hash`.


## Процесс

- TDD: сначала падающий тест, потом код. Тесты в `commonTest` для `libs/core`/`libs/drive`, `desktopTest` для `libs/ui`.
- Шифрование путей — **по сегментам** (`a/b/c.md` → три зашифрованных сегмента).
- Коммиты — conventional-стиль, как в плане (`feat(core): …`, `chore: …`, `ci: …`).
- Прогресс выполнения плана ведётся в `.superpowers/sdd/<план>/progress.md` (git-ignored); отклонения от плана записываются как `Ruling:`.
- **Лаконичность промптов для сессий и субагентов:** Не дублировать описание проекта, стек, ОС, правила хоста (`rtk`) и инструкции по окружению — всё это уже зафиксировано в `AGENTS.md` и правилах хоста. Промпт обязан содержать строго целевую команду/скилл, саму задачу и ссылки на спецификацию/план.
- **Делегирование во внешний CLI и работа с Worktrees** (только по явной просьбе пользователя в текущем запросе; по умолчанию агент работает сам и на своей модели, субагенты — встроенными средствами своего инструмента):
  - Пример запуска фонового агента Claude Code: `claude --model claude-sonnet-5-5 --effort medium --bg "<prompt>"`.
  - При остановке агента из-за квоты (`HTTP 429 rate_limit`) или сбое контекста: другой агент может продолжить работу непосредственно в изолированном ворктри (`.claude/worktrees/<name>` на ветке `worktree-<name>`).
  - После завершения всех задач и прогона тестов ветка вливается в `main` (fast-forward merge), ворктри освобождается (`rtk git worktree unlock <path> && rtk git worktree remove <path>`), а сессия удаляется (`rtk claude rm <id>`).

## Релизы

- Версионирование — Release Please (`.github/release-please-config.json`, `.github/.release-please-manifest.json`). Версия одна на все модули — `mimir.version` в `gradle.properties` (обновляется автоматически через `extra-files` типа `generic` и маркеры `x-release-please-*` в `gradle.properties`); тег строго `v<версия>` (`include-component-in-tag: false`).
- Изменение, влияющее на релиз, сопровождается Conventional Commit (`feat`, `fix`, `perf`, `refactor`; `docs`/`chore`/`ci` в changelog не попадают). Файлы `.changeset/` и `package.json` в модулях не используются.
- Поток: push в `main` → `release-please.yml` (с поддержкой `secrets.RELEASE_PLEASE_TOKEN || secrets.GITHUB_TOKEN` для автозапуска CI на PR без `action_required`) ведёт PR «chore(main): release <версия>» → после слияния создаётся тег `v<версия>` и `release.yml` вызывается напрямую из `release-please.yml` через `workflow_call` (matrix-сборка msi/apk/zip, Arch в контейнере, Docker в GHCR, прикрепление артефактов к GitHub Release). Триггера на push тега у `release.yml` нет (при `RELEASE_PLEASE_TOKEN` релиз запустился бы дважды). Проверка релизной сборки без публикации: `gh workflow run release.yml --ref <ветка> -f tag=v<версия> -f dry_run=true`.
- Версию в `gradle.properties` руками не менять — только через PR Release Please.

## Проектные скиллы (`.agents/skills/`)

Единый источник скиллов для всех агентов (Antigravity, Claude Code, Codex) — `.agents/skills/<имя>/`. `.claude/skills/<имя>` — только относительный симлинк `../../.agents/skills/<имя>`: так скилл видит Claude Code. Новый скилл создаётся в `.agents/skills`, затем `mise run skills:sync` создаёт симлинк; скилл, по ошибке созданный в `.claude/skills`, команда сама переносит в `.agents/skills`. Расхождение ловят `mise run skills:check`, `ci:fast` и `detect-changes` в CI. Исключение — `impeccable`: установщик генерирует отдельную версию под каждый инструмент (`.claude/skills/impeccable` — с `/impeccable` и AskUserQuestion, `.agents/skills/impeccable` — с `$impeccable` и Codex-агентами). Эти копии не правятся и не сливаются руками, обновляются только переустановкой; список таких скиллов — `VENDOR` в `.agents/sync-skills.mjs`.

- `mimir-cross-platform` — поведение на каждой платформе, платформенные сервисы за интерфейсом.
- `mimir-design-patterns` — каталог паттернов проекта и куда класть новый код.
- `mimir-sync-io` — стоимость обращений к Drive и чтения файлов, асинхронность, параллелизм.
- `mimir-ui-ux` — экраны, состояния, тексты, оформление и визуальная проверка.
- `mimir-ui-test` — автоматизированное headless-тестирование и скриншоты UI десктопа и веб-расширения.
- `mimir-build-verify` — как собрать и проверить изменения на Arch, что делать без Android SDK.
- `mimir-ci-scope` — CI/CD, workflows, релиз, скорость сборки: закрытая карта CI-файлов и порядок чтения (не обходить код приложения).
- `mimir-add-module` — добавить новый Gradle-модуль по конвенциям проекта.
- `mimir-storage-adapter` — добавить адаптер облачного хранилища (порт `RemoteStorageProvider`).
- `mimir-identifiers` — добавить/изменить долгоживущий идентификатор без дублирования.

## 🗺 Архитектурная карта и навигация (OpenViking L0-L2)

Проект `mimir` описан трехуровневой базой знаний context-init (L0-L2), которая лежит в `.context-init/knowledge/` и коммитится вместе с кодом. Людям можно читать её напрямую; агентам — предпочитать MCP/`pack` ниже: это компактнее и учитывает бюджет токенов. После структурных изменений запустите `context-init update` и закоммитьте изменения базы в том же PR.

### ⚡ MCP-First Protocol (context-init)

1. `context_get_subsystems` — карта подсистем проекта (L0).
2. `context_get_subsystem(subsystem_id)` — компактная L1-карточка подсистемы.
3. `context_pack(subsystem_id, compress=true)` — AST-сжатый код подсистемы.
4. `context_check` — проверка архитектурного дрейфа перед сдачей задачи.

Без доступа к MCP (субагенты, скрипты): `rtk context-init pack <subsystem_id> --compress` и `rtk context-init check --compact`.

### 📦 Доступные подсистемы:
- `apps_android` (Android) — Подсистема `apps/android` с ключевыми точками входа: apps/android/build.gradle.kts
- `apps_browser-extension` (Browser Extension) — Подсистема `apps/browser-extension` с ключевыми точками входа: apps/browser-extension/build.gradle.kts
- `apps_browser-extension_jsmain` (Browser Extension JsMain) — Модуль и компоненты директории `apps/browser-extension/src/jsMain` (3 файлов)
- `apps_browser-extension_jsmain_kotlin_dev_mimir_extension` (Browser Extension JsMain Kotlin Dev Mimir Extension) — Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension` (8 файлов)
- `apps_browser-extension_jsmain_kotlin_dev_mimir_extension_ui` (Browser Extension JsMain Kotlin Dev Mimir Extension Ui) — Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui` (8 файлов)
- `apps_daemon` (Daemon) — Подсистема `apps/daemon` с ключевыми точками входа: apps/daemon/build.gradle.kts
- `apps_desktop` (Desktop) — Подсистема `apps/desktop` с ключевыми точками входа: apps/desktop/build.gradle.kts
- `apps_desktop_main_kotlin_dev_mimir_desktop` (Desktop Main Kotlin Dev Mimir Desktop) — Модуль и компоненты директории `apps/desktop/src/main/kotlin/dev/mimir/desktop` (15 файлов)
- `apps_desktop_main_kotlin_dev_mimir_desktop_tray` (Desktop Main Kotlin Dev Mimir Desktop Tray) — Модуль и компоненты директории `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray` (10 файлов)
- `apps_obsidian-plugin` (Obsidian Plugin) — Подсистема `apps/obsidian-plugin` с ключевыми точками входа: apps/obsidian-plugin/build.gradle.kts
- `apps_web-ui` (Web Ui) — Подсистема `apps/web-ui` с ключевыми точками входа: apps/web-ui/build.gradle.kts
- `build-logic` (Build Logic) — Подсистема `build-logic` с ключевыми точками входа: build-logic/build.gradle.kts
- `design` (Design) — Модуль и компоненты директории `design` (3 файлов)
- `libs_client` (Client) — Подсистема `libs/client` с ключевыми точками входа: libs/client/build.gradle.kts
- `libs_core` (Core) — Подсистема `libs/core` с ключевыми точками входа: libs/core/build.gradle.kts
- `libs_core_commonmain_kotlin_dev_mimir_core` (Core CommonMain Kotlin Dev Mimir Core) — Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core` (15 файлов)
- `libs_core_commonmain_kotlin_dev_mimir_core_crypto` (Core CommonMain Kotlin Dev Mimir Core Crypto) — Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов)
- `libs_core_commonmain_kotlin_dev_mimir_core_sync` (Core CommonMain Kotlin Dev Mimir Core Sync) — Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/sync` (13 файлов)
- `libs_drive` (Drive) — Подсистема `libs/drive` с ключевыми точками входа: libs/drive/build.gradle.kts
- `libs_runtime` (Runtime) — Подсистема `libs/runtime` с ключевыми точками входа: libs/runtime/build.gradle.kts
- `libs_runtime_commonmain_kotlin_dev_mimir_runtime` (Runtime CommonMain Kotlin Dev Mimir Runtime) — Модуль и компоненты директории `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime` (15 файлов)
- `libs_runtime_commonmain_kotlin_dev_mimir_runtime_update` (Runtime CommonMain Kotlin Dev Mimir Runtime Update) — Модуль и компоненты директории `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update` (6 файлов)
- `libs_ui` (Ui) — Подсистема `libs/ui` с ключевыми точками входа: libs/ui/build.gradle.kts
- `libs_ui_commonmain_kotlin_dev_mimir_ui` (Ui CommonMain Kotlin Dev Mimir Ui) — Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов)
- `libs_ui_commonmain_kotlin_dev_mimir_ui_screens` (Ui CommonMain Kotlin Dev Mimir Ui Screens) — Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens` (13 файлов)
- `packaging` (Packaging) — Модуль и компоненты директории `packaging` (2 файлов)
