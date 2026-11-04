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
| `core` | чистый Kotlin (jvm, js, wasmJs, android): порты, шифрование, локальная ФС, движок синхронизации, фейки для тестов |
| `drive` | адаптер Google Drive: HTTP, OAuth, путь→id, загрузка |
| `runtime` | общая логика демона и Android (KMP jvm/android): движок, вход в Google, база состояния, планировщик |
| `daemon` | единый бэкенд: сборка движка, HTTP API, планировщик, вход в Google (встраивается в desktop или работает отдельно/в Docker) |
| `ui` | общий Compose-интерфейс (desktop + wasmJs, Android при SDK) |
| `app-desktop` | окно, трей, выбор папки, браузер; встроенный или удалённый демон |
| `app-android`, `web-ui`, `obsidian-plugin`, `browser-extension` | тонкие точки входа (пока заготовки) |
| `packaging/`, `.github/` | Arch PKGBUILD, Docker, CI (ubuntu/windows/macos) и релиз |

Зависимости идут только вниз: точки входа → `ui` → `drive` → `core`. `core` не знает ничего о платформе.

## Карта кода (где искать)

Пакеты — `dev.mimir.<module>.<area>`; тест класса `X` — `XTest.kt` в соседнем `*Test`-наборе. Сначала смотреть сюда, потом искать (`rg "class X"`).

| Что | Где |
|---|---|
| Контракты (порты) | `core/…/core/api/`: `RemoteStorage`, `LocalVault`, `AuthProvider`, `SecretStore`, `NetworkState`, `RemoteStorageProvider`/`Registry` |
| Шифрование | `core/…/crypto/`: `RcloneCipher`, `EncryptedRemoteStorage` (декоратор), примитивы |
| Локальная ФС | `core/…/fs/`: `FileSystemLocalVault`, `VaultWatcher` (+ `jvmMain/fs` NIO, `androidMain`, `jsMain`), `VaultPathFilter`, `EchoFilter` |
| Синхронизация | `core/…/sync/`: решение — `SyncPlanBuilder` → `SyncPlan`; исполнение — `DefaultSyncEngine`; `ConflictResolver`, `SyncIndex` + `SyncStateStore`, `SyncProgress`, `RemoteExceptions` |
| Правила синхронизации | `core/…/rules/` (`SyncRules`, `GlobMatcher`) |
| Фейки для тестов | `core/…/testing/`: `InMemoryRemoteStorage`, `InMemoryLocalVault`; UI — `ui/model/FakeMimirUiModel` |
| Google Drive | `drive/`: порт — `GoogleDriveStorage`; токены, retry, ошибки сети — `client/DriveHttpClient`; OAuth/PKCE — `auth/`; путь→id и кэш — `path/DrivePathResolver`; загрузка (resumable) — `upload/DriveUploader` |
| Runtime | `runtime/`: фасад — `MimirRuntime`; сборка движка — `EngineAssembler`; вход — `login/`; настройки — `JsonRuntimeSettingsStore` |
| Демон | composition root — `DaemonRuntime`; API — `Routes` + `ProgressDto`; конфиг, env и каталоги ОС — `DaemonConfig`; расписание — `SyncScheduler`; вход через браузер — `BrowserLoginReceiver`; индекс — `JsonSyncStateStore`, атомарная запись — `AtomicFiles.kt` |
| UI и дизайн-система | контракт — `ui/model/MimirUiModel`; экраны — `ui/screens/`; крафтовые компоненты — `ui/components/MimirComponents.kt`; токен-константы — `ui/MimirTokens.kt`; темы — `MimirTheme`, `ObsidianStyle`; тексты — `SyncTexts`; сервисы (CompositionLocal) — `ui/picker/`, `ui/platform/` |
| Дизайн-токены (Single Source of Truth) | `design/tokens.json`; генератор — `scripts/generate-tokens.mjs` (`pnpm run tokens:generate` / `tokens:check`); артефакты — `ui/MimirTokens.kt`, `browser-extension/.../tokens.css` |
| Desktop | composition root — `app-desktop/Main.kt`; встроенный/удалённый демон — `DaemonServiceFactory`; HTTP-клиент — `HttpDaemonClient`; трей — `tray/`; выбор папки — `picker/`; браузер — `SystemBrowserOpener` |
| Сборка | convention-плагины — `build-logic/src/main/kotlin/`; версии — `gradle/libs.versions.toml`; идентификаторы — `gradle.properties` |

## Правила

- **Идентификаторы** (`mimir.appName`, `mimir.version`, `mimir.group`, `mimir.appId`, `mimir.obsidianPluginId`, `mimir.pacmanName`, `mimir.dockerImage`, `mimir.msiUpgradeUuid`, `mimir.apiDefaultPort`) живут только в `gradle.properties`. В код и манифесты попадают через BuildConfig / `processResources` / генерацию, литералами не дублируются.
- Пакет: `dev.mimir.<module>`. Версии зависимостей — только в `gradle/libs.versions.toml`.
- Gradle: Kotlin DSL, configuration cache и build cache включены — не ломать их.
- **Android SDK необязателен**: без него `mise run build` обязан проходить, пропуская Android-таргеты с предупреждением `Android SDK not found: skipping Android targets`. Не добавлять безусловных ссылок на `:app-android` и Android-плагины.
- **Секреты и окружение Gradle Daemon**: секреты живут только в `mise.local.toml` (в `.gitignore`) и GitHub Secrets. Сборка и тесты проходят без единого секрета. Задачи `JavaExec` (`:app-desktop:run`, `:daemon:run`) обязаны явно синхронизировать переменные `MIMIR_*` через `providers.environmentVariablesPrefixedBy("MIMIR_")`, очищая унаследованные ключи демона. При кардинальной смене переменных в `mise.local.toml` выполнять `./gradlew --stop`.
- **Docker-образ демона** строится на glibc-базе (`eclipse-temurin:27-jdk` / `27-jre`), а не на alpine: Kotlin/JS скачивает Node под glibc.
- **Android**: нужны AGP ≥ 9.4 и compileSdk 37 (иначе jlink `JdkImageTransform` падает на JDK 27, а Compose 1.12 требует compileSdk 37).
- Шифрование реализовано (`RcloneCipher`, совместим с rclone crypt, чистый Kotlin в `core`; `expect/actual` в `core` — только источник случайности и проверка символьных ссылок). Синхронизация, Google Drive и OAuth реализованы; заглушками (`NotImplementedError` с осмысленным сообщением) остаются `StubSyncEngine` и точки входа Android/web/плагина/расширения. Пароль в `RcloneCipher` передаётся в открытом виде; golden-векторы `RcloneVectors.kt` генерирует `scripts/gen-rclone-vectors.sh` (нужен rclone), обычные сборка и тесты rclone не требуют.
- **Google OAuth-клиенты**: две пары — `MIMIR_GOOGLE_DESKTOP_CLIENT_ID/SECRET` (вход через браузер) и `MIMIR_GOOGLE_DEVICE_CLIENT_ID/SECRET` (Device Code); старые `MIMIR_GOOGLE_CLIENT_*` — алиас Device-пары. Вшитые значения (`MIMIR_BUILD_GOOGLE_*`) живут только в BuildConfig `daemon` и `app-android` (Desktop-пара допустима в BuildConfig `app-android` для fallback-входа через браузер; не в `core`: он попадает в JS-бандлы), в Docker-образ не вшиваются — Docker-демон настраивается переменными окружения. В локальной разработке `app-android/build.gradle.kts` читает `MIMIR_BUILD_GOOGLE_DESKTOP_*` с fallback на `MIMIR_GOOGLE_DESKTOP_*` из `mise.local.toml`. Для нативного системного входа на Android (GIS) в Google Cloud Console регистрируется OAuth-клиент типа Android с пакетом `dev.mimir.app` и SHA-1 ключа (debug: `84:05:F0:7B:51:7F:DA:C0:F7:7B:9D:B3:27:F6:37:97:C1:A4:56:3C`); при его отсутствии активируется fallback в браузер через Desktop-клиент.
- **Linux-лаунчеры** (`run` в `app-desktop/build.gradle.kts`, `packaging/arch/mimir.sh`) выставляют `_JAVA_AWT_WM_NONREPARENTING=1` — иначе под niri окно рисуется только в левом верхнем углу.
- **Десктопное оформление**: главное окно использует бесшовный заголовок на Compose (Obsidian CSD) без стандартного белого заголовка ОС; трей — Compose-поповер (`Window.Type.POPUP` для защиты от тайлинга на Linux) с сохранением D-Bus меню для Linux-панелей.
- `SyncStatus` включает `Offline` (нет сети); прогресс — `SyncEngine.progress`. Ошибка одного файла не прерывает синхронизацию: она попадает в проблемы и повторяется в следующем прогоне.
- Защита от удаления: при пустом удалённом списке и непустом индексе движок прерывает прогон (`SyncAbortedException`), ничего не трогая; каждый `DeleteLocal` перед выполнением перепроверяется через `stat` (файл найден или проверка не удалась — локальный файл не удаляется). `GoogleDriveStorage.listAll` сбрасывает кэши путей, чтобы `stat` не был устаревшим. `GoogleDriveStorage.delete` переносит файл в корзину Drive, а не стирает его.
- Параллельность синхронизации: `DefaultSyncEngine(parallelism = 4, dispatcher = Dispatchers.IO)` из `DaemonRuntime`; настройка `syncParallelism` / `MIMIR_SYNC_PARALLELISM` (1..16). `Dispatchers.IO` передаётся только из `daemon`, в `core`/`drive` commonMain диспетчер — параметр.
- Vault не ходит по символьным ссылкам: их пути отдаёт `LocalVault.hiddenPaths()`, и план синхронизации пропускает всё под ними, как исключения (иначе файл за ссылкой выглядит удалённым и стирается в Drive).
- Строка статуса до подключения — ровно `Mimir: not connected` (`SyncStatus.label()`).
- Локальный HTTP API: `127.0.0.1:47821`, адрес/порт настраиваются; занятый порт → сообщение `Port <port> on <bind> is already in use`, код 2, без стектрейса.
- Часы — `kotlin.time.Clock`; отдельного порта `Clock` нет.
- **Kotlinx Serialization DTO и обратная совместимость**: При добавлении опциональных полей со значением по умолчанию (особенно в разделяемые DTO ответов ошибок вроде `DaemonErrorBody`) обязательно использовать аннотацию `@EncodeDefault(EncodeDefault.Mode.NEVER)`. Ktor по умолчанию использует `explicitNulls = true`, и без аннотации поле `val code: String? = null` сериализуется как `"code": null`, что ломает точные строковые проверки в существующих тестах и сторонних клиентах.
- Платформы: Linux — Arch + системная Java (`java-runtime>=27`); Windows — MSI per-user с вшитой JRE, ярлыками на рабочем столе и в меню Пуск, и диалогом выбора папки; Android — APK вне Play, `MANAGE_EXTERNAL_STORAGE`; браузер — только Chromium, MV3; плагин Obsidian не `isDesktopOnly` и не использует ничего, кроме модуля `obsidian`.
- **Браузерное расширение (`:browser-extension`, Chromium MV3)**: работает автономно (без локального демона) поверх `:core` и `:drive` (Kotlin/JS). Основной интерфейс — `chrome.sidePanel` (пошаговый переход Список → Заметка) и полноэкранная двухколоночная вкладка (`popup.html?mode=tab`) с fallback в Popup; встраивание в DOM `drive.google.com` не используется. Деривированный 80-байтный ключ `RcloneCipher` живёт только в RAM (`chrome.storage.session`), а в `chrome.storage.local` кэшируются исключительно зашифрованные `RemoteEntry` и `.canary_hash`. Для стабильного extension ID в `gradle.properties` задаётся `mimir.extensionPublicKey`; вход — PKCE через Desktop-клиент с loopback-redirect `http://127.0.0.1:47999/callback` (страница входа открывается во вкладке, переход перехватывается `chrome.tabs.onUpdated`, регистрировать redirect в Google Cloud не нужно) и резервный Device Code Flow; корневая папка Drive берётся из `MIMIR_ROOT_FOLDER` при сборке.
- **Тестирование корутин и фоновых компонентов**: При тестировании классов с долгоживущими корутинами или периодическими тикерами (`MimirRuntime`, `SyncScheduler`, бесконечные `flow.collect`) категорически запрещено вызывать `testScheduler.advanceUntilIdle()` в `runTest` — это приводит к бесконечному циклу прокрутки виртуального времени. Использовать явный сдвиг на фиксированное время (`advanceTimeBy`), изолированные фейки контрактов (`SyncRunner`) вместо полного рантайма или завершать `scope.cancel()` перед окончанием теста.
- **Kotlin/JS исключения и UTF-8**: В JS-рантайме нативные JavaScript ошибки (в частности, `TypeError: URI malformed` при невалидной UTF-8 последовательности в `decodeToString(throwOnInvalidSequence = true)` или `Buffer.readString()`) наследуются от `Throwable` и нативного `Error`, но НЕ являются подклассами `kotlin.Exception`. Блоки `catch (e: Exception)` пропускают их, приводя к необработанным крашам тестов. В коде шифрования, валидации канареек и чтения внешних потоков перехватывать `Throwable` с обязательным пробросом `CancellationException`.
- **Compose Multiplatform UI Preview**: В кроссплатформенном модуле `:ui` для аннотации `@Preview` в `commonMain` использовать строго DSL Compose-плагина `implementation(compose.components.uiToolingPreview)`. Замена на внешний Maven-артефакт `org.jetbrains.compose.ui:ui-tooling-preview` ломает таргет `wasmJs`, в котором этот артефакт отсутствует.
- **CI Runners, Java и Symlinks на Windows**: На раннерах `windows-latest` GitHub Actions системная переменная `JAVA_HOME` по умолчанию выставлена на Java 17; плагин `mise-action` не переопределяет её — шаги сборки обязаны нормализовать `java_home=$(mise where java | tr '\\' '/')` и экспортировать в `$GITHUB_ENV` и `$GITHUB_PATH`. Для корректной работы Kotlin/JS (Yarn Workspaces symlinks в `node_modules`) и глубокой вложенности пакетов на Windows-раннере обязательно включать Developer Mode (`AllowDevelopmentWithoutDevLicense = 1`), `LongPathsEnabled = 1` и `git config --global core.symlinks true / core.longpaths true` перед шагом сборки.
- **Windows MSI обновления и лаунчер**: Инсталлятор Windows (`jpackage` / WiX) выполняет автоматический `MajorUpgrade` только при повышении версии (`0.2.1 > 0.2.0`). Переустановка нового билда с идентичной версией блокируется Windows Installer (ошибка 1638: «Уже установлена другая версия этого продукта») и при локальном тестировании требует предварительного удаления старой версии. В конфиг `<app>.cfg` jpackage jvm-лаунчера на Windows добавляется `win.norestart=true`, исключающий дублирование процесса-обёртки в Диспетчере задач.
- **Karma ChromeHeadless и таймауты в CI**: Тесты браузерного расширения (`:browser-extension:jsBrowserTest`) в Linux-контейнерах CI требуют флагов `--no-sandbox --disable-setuid-sandbox --disable-dev-shm-usage --disable-gpu --headless=new` (`headless.js`), а из-за вычислений scrypt в JS — таймаута mocha не менее 120 с (`browser-extension/karma.config.d/mocha-timeout.js`), аналогично модулю `:core`.
- **Скачивание и публикация релизных артефактов**: `actions/download-artifact@v4` обязан вызывать скачивание строго по именам артефактов (`name: msi`, `name: apk`, etc.), исключая захват служебных файлов Docker Buildx. Перед формированием `sha256sums.txt` файлы из поддиректорий `dist` должны сплющиваться в корень каталога, а пустые директории удаляться.

## Процесс

- TDD: сначала падающий тест, потом код. Тесты в `commonTest` для `core`/`drive`, `desktopTest` для `ui`.
- Шифрование путей — **по сегментам** (`a/b/c.md` → три зашифрованных сегмента).
- Коммиты — conventional-стиль, как в плане (`feat(core): …`, `chore: …`, `ci: …`).
- Прогресс выполнения плана ведётся в `.superpowers/sdd/<план>/progress.md` (git-ignored); отклонения от плана записываются как `Ruling:`.
- **Лаконичность промптов для сессий и субагентов:** Не дублировать описание проекта, стек, ОС, правила хоста (`rtk`) и инструкции по окружению — всё это уже зафиксировано в `AGENTS.md` и правилах хоста. Промпт обязан содержать строго целевую команду/скилл, саму задачу и ссылки на спецификацию/план.
- **Делегирование в Claude Code CLI и работа с Worktrees**:
  - Запуск фонового агента: `claude --model claude-sonnet-5-5 --effort medium --bg "<prompt>"`.
  - При остановке агента из-за квоты (`HTTP 429 rate_limit`) или сбое контекста: другой агент может продолжить работу непосредственно в изолированном ворктри (`.claude/worktrees/<name>` на ветке `worktree-<name>`).
  - После завершения всех задач и прогона тестов ветка вливается в `main` (fast-forward merge), ворктри освобождается (`rtk git worktree unlock <path> && rtk git worktree remove <path>`), а сессия удаляется (`rtk claude rm <id>`).

## Релизы

- Версионирование — changesets (`.changeset/`, `package.json` в каждом модуле только как носитель версии). Все модули в группе `fixed`: версия одна, `pnpm run version` синхронизирует её в `mimir.version` (`gradle.properties`); `pnpm run version:check` проверяет рассинхрон.
- Изменение, влияющее на релиз, сопровождается `pnpm changeset`.
- Поток: push в `main` → `changesets.yml` ведёт PR «chore: version packages» → после слияния `scripts/tag-release.mjs` ставит тег `v<версия>` и вызывает `release.yml` (matrix-сборка msi/apk/zip, Arch в контейнере, Docker в GHCR, публикация в GitHub Release).
- Версию в `gradle.properties` руками не менять — только через changesets.

## Проектные скиллы (`.claude/skills/`)

- `mimir-cross-platform` — поведение на каждой платформе, платформенные сервисы за интерфейсом.
- `mimir-design-patterns` — каталог паттернов проекта и куда класть новый код.
- `mimir-sync-io` — стоимость обращений к Drive и чтения файлов, асинхронность, параллелизм.
- `mimir-ui-ux` — экраны, состояния, тексты, оформление и визуальная проверка.
- `mimir-ui-test` — автоматизированное headless-тестирование и скриншоты UI десктопа и веб-расширения.
- `mimir-build-verify` — как собрать и проверить изменения на Arch, что делать без Android SDK.
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
- `app-android` (App Android) — Подсистема `app-android` с ключевыми точками входа: app-android/package.json
- `app-desktop` (App Desktop) — Подсистема `app-desktop` с ключевыми точками входа: app-desktop/package.json
- `browser-extension` (Browser Extension) — Подсистема `browser-extension` с ключевыми точками входа: browser-extension/package.json
- `build-logic` (Build Logic) — Модуль и компоненты директории `build-logic` (5 файлов)
- `client` (Client) — Подсистема `client` с ключевыми точками входа: client/package.json
- `core` (Core) — Подсистема `core` с ключевыми точками входа: core/package.json
- `core_commonmain_kotlin_dev_mimir_core` (Core CommonMain Kotlin Dev Mimir Core) — Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core` (15 файлов)
- `core_commonmain_kotlin_dev_mimir_core_crypto` (Core CommonMain Kotlin Dev Mimir Core Crypto) — Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов)
- `daemon` (Daemon) — Подсистема `daemon` с ключевыми точками входа: daemon/package.json
- `design` (Design) — Модуль и компоненты директории `design` (1 файлов)
- `docs` (Docs) — Модуль и компоненты директории `docs` (15 файлов)
- `drive` (Drive) — Подсистема `drive` с ключевыми точками входа: drive/package.json
- `gradle` (Gradle) — Модуль и компоненты директории `gradle` (1 файлов)
- `obsidian-plugin` (Obsidian Plugin) — Подсистема `obsidian-plugin` с ключевыми точками входа: obsidian-plugin/package.json
- `packaging` (Packaging) — Модуль и компоненты директории `packaging` (1 файлов)
- `runtime` (Runtime) — Подсистема `runtime` с ключевыми точками входа: runtime/package.json
- `scripts` (Scripts) — Модуль и компоненты директории `scripts` (4 файлов)
- `ui` (Ui) — Подсистема `ui` с ключевыми точками входа: ui/package.json
- `ui_commonmain_kotlin_dev_mimir_ui` (Ui CommonMain Kotlin Dev Mimir Ui) — Модуль и компоненты директории `ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов)
- `ui_commonmain_kotlin_dev_mimir_ui_screens` (Ui CommonMain Kotlin Dev Mimir Ui Screens) — Модуль и компоненты директории `ui/src/commonMain/kotlin/dev/mimir/ui/screens` (12 файлов)
- `web-ui` (Web Ui) — Подсистема `web-ui` с ключевыми точками входа: web-ui/package.json
