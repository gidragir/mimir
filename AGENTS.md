# Mimir — руководство для агентов

Mimir — клиент синхронизации Obsidian-хранилища с шифрованием поверх Google Drive. Спека: `docs/superpowers/specs/2026-10-23-mimir-design.md`, план скелета: `docs/superpowers/plans/2026-10-23-mimir-skeleton.md`. Спека — первичный источник; при конфликте с планом прав спека.

## Среда

- **Машина разработки — Arch Linux** (bash). На Windows-ПК пользователя ничего не устанавливать и не запускать (ни gradle, ни mise, ни JDK). Windows-специфичное (MSI) проверяется только в CI. Без явного разрешения пользователя сессия на Windows = «только писать файлы».
- Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8. Откат компонента на JDK 25 допустим только с записью причины в README («Версии»).
- Запуск через `mise run <задача>` (см. `mise.toml`); у каждой задачи есть `run_windows`.

## Инварианты Mimir (в каждой задаче, без напоминаний)

Хост разработки (Arch + niri) — **одна из целей, а не эталон**. Проект большой и долгоживущий: решения принимаются по паттернам, а не «как быстрее заработает здесь».

1. **Платформы** — Linux (любой DE/WM, не только niri), Windows, Android, Docker-демон без дисплея, JS-клиенты (Obsidian, расширение, web-ui). → скилл `mimir-cross-platform`.
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
| UI | контракт — `ui/model/MimirUiModel`; экраны — `ui/screens/`; тексты — `SyncTexts`; тема — `MimirTheme`, `ObsidianStyle`; платформенные сервисы (CompositionLocal) — `ui/picker/`, `ui/platform/` |
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
- **Google OAuth-клиенты**: две пары — `MIMIR_GOOGLE_DESKTOP_CLIENT_ID/SECRET` (вход через браузер) и `MIMIR_GOOGLE_DEVICE_CLIENT_ID/SECRET` (Device Code); старые `MIMIR_GOOGLE_CLIENT_*` — алиас Device-пары. Вшитые значения (`MIMIR_BUILD_GOOGLE_*`) живут только в BuildConfig `daemon` и `app-android` (Desktop-пара допустима в BuildConfig `app-android` для fallback-входа через браузер; не в `core`: он попадает в JS-бандлы), в Docker-образ не вшиваются — Docker-демон настраивается переменными окружения.
- **Linux-лаунчеры** (`run` в `app-desktop/build.gradle.kts`, `packaging/arch/mimir.sh`) выставляют `_JAVA_AWT_WM_NONREPARENTING=1` — иначе под niri окно рисуется только в левом верхнем углу.
- `SyncStatus` включает `Offline` (нет сети); прогресс — `SyncEngine.progress`. Ошибка одного файла не прерывает синхронизацию: она попадает в проблемы и повторяется в следующем прогоне.
- Защита от удаления: при пустом удалённом списке и непустом индексе движок прерывает прогон (`SyncAbortedException`), ничего не трогая; каждый `DeleteLocal` перед выполнением перепроверяется через `stat` (файл найден или проверка не удалась — локальный файл не удаляется). `GoogleDriveStorage.listAll` сбрасывает кэши путей, чтобы `stat` не был устаревшим. `GoogleDriveStorage.delete` переносит файл в корзину Drive, а не стирает его.
- Параллельность синхронизации: `DefaultSyncEngine(parallelism = 4, dispatcher = Dispatchers.IO)` из `DaemonRuntime`; настройка `syncParallelism` / `MIMIR_SYNC_PARALLELISM` (1..16). `Dispatchers.IO` передаётся только из `daemon`, в `core`/`drive` commonMain диспетчер — параметр.
- Vault не ходит по символьным ссылкам: их пути отдаёт `LocalVault.hiddenPaths()`, и план синхронизации пропускает всё под ними, как исключения (иначе файл за ссылкой выглядит удалённым и стирается в Drive).
- Строка статуса до подключения — ровно `Mimir: not connected` (`SyncStatus.label()`).
- Локальный HTTP API: `127.0.0.1:47821`, адрес/порт настраиваются; занятый порт → сообщение `Port <port> on <bind> is already in use`, код 2, без стектрейса.
- Часы — `kotlin.time.Clock`; отдельного порта `Clock` нет.
- Платформы: Linux — Arch + системная Java (`java-runtime>=27`); Windows — MSI per-user с вшитой JRE; Android — APK вне Play, `MANAGE_EXTERNAL_STORAGE`; браузер — только Chromium, MV3; плагин Obsidian не `isDesktopOnly` и не использует ничего, кроме модуля `obsidian`.
- **Тестирование корутин и фоновых компонентов**: При тестировании классов с долгоживущими корутинами или периодическими тикерами (`MimirRuntime`, `SyncScheduler`, бесконечные `flow.collect`) категорически запрещено вызывать `testScheduler.advanceUntilIdle()` в `runTest` — это приводит к бесконечному циклу прокрутки виртуального времени. Использовать явный сдвиг на фиксированное время (`advanceTimeBy`), изолированные фейки контрактов (`SyncRunner`) вместо полного рантайма или завершать `scope.cancel()` перед окончанием теста.

## Процесс

- TDD: сначала падающий тест, потом код. Тесты в `commonTest` для `core`/`drive`, `desktopTest` для `ui`.
- Шифрование путей — **по сегментам** (`a/b/c.md` → три зашифрованных сегмента).
- Коммиты — conventional-стиль, как в плане (`feat(core): …`, `chore: …`, `ci: …`).
- Прогресс выполнения плана ведётся в `.superpowers/sdd/<план>/progress.md` (git-ignored); отклонения от плана записываются как `Ruling:`.
- **Лаконичность промптов для сессий и субагентов:** Не дублировать описание проекта, стек, ОС, правила хоста (`rtk`) и инструкции по окружению — всё это уже зафиксировано в `AGENTS.md` и правилах хоста. Промпт обязан содержать строго целевую команду/скилл, саму задачу и ссылки на спецификацию/план.

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
- `mimir-build-verify` — как собрать и проверить изменения на Arch, что делать без Android SDK.
- `mimir-add-module` — добавить новый Gradle-модуль по конвенциям проекта.
- `mimir-storage-adapter` — добавить адаптер облачного хранилища (порт `RemoteStorageProvider`).
- `mimir-identifiers` — добавить/изменить долгоживущий идентификатор без дублирования.
