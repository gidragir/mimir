# Mimir — руководство для агентов

Mimir — клиент синхронизации Obsidian-хранилища с шифрованием поверх Google Drive. Спека: `docs/superpowers/specs/2026-10-23-mimir-design.md`, план скелета: `docs/superpowers/plans/2026-10-23-mimir-skeleton.md`. Спека — первичный источник; при конфликте с планом прав спека.

## Среда

- **Машина разработки — Arch Linux** (bash). На Windows-ПК пользователя ничего не устанавливать и не запускать (ни gradle, ни mise, ни JDK). Windows-специфичное (MSI) проверяется только в CI. Без явного разрешения пользователя сессия на Windows = «только писать файлы».
- Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8. Откат компонента на JDK 25 допустим только с записью причины в README («Версии»).
- Запуск через `mise run <задача>` (см. `mise.toml`); у каждой задачи есть `run_windows`.

## Структура

| Модуль | Назначение |
|---|---|
| `build-logic` | convention-плагины: `mimir.settings`, `mimir.android-support`, `mimir.kmp-library`; `AndroidSdk` |
| `core` | чистый Kotlin (jvm, js, wasmJs, android): порты, реестр облаков, `EncryptedRemoteStorage`, `RcloneCipher`, заглушки |
| `drive` | адаптер Google Drive (заглушка) |
| `ui` | общий Compose-интерфейс (`MimirApp`, `StatusScreen`) |
| `app-desktop`, `app-android`, `web-ui`, `daemon`, `obsidian-plugin`, `browser-extension` | тонкие точки входа (задачи 5–10 плана) |
| `packaging/`, `.github/` | Arch PKGBUILD, Docker, CI/релиз |

Зависимости идут только вниз: точки входа → `ui` → `drive` → `core`. `core` не знает ничего о платформе.

## Правила

- **Идентификаторы** (`mimir.appName`, `mimir.version`, `mimir.group`, `mimir.appId`, `mimir.obsidianPluginId`, `mimir.pacmanName`, `mimir.dockerImage`, `mimir.msiUpgradeUuid`, `mimir.apiDefaultPort`) живут только в `gradle.properties`. В код и манифесты попадают через BuildConfig / `processResources` / генерацию, литералами не дублируются.
- Пакет: `dev.mimir.<module>`. Версии зависимостей — только в `gradle/libs.versions.toml`.
- Gradle: Kotlin DSL, configuration cache и build cache включены — не ломать их.
- **Android SDK необязателен**: без него `mise run build` обязан проходить, пропуская Android-таргеты с предупреждением `Android SDK not found: skipping Android targets`. Не добавлять безусловных ссылок на `:app-android` и Android-плагины.
- **Секреты** только в `mise.local.toml` (в `.gitignore`) и GitHub Secrets. Сборка и тесты проходят без единого секрета.
- **Docker-образ демона** строится на glibc-базе (`eclipse-temurin:27-jdk` / `27-jre`), а не на alpine: Kotlin/JS скачивает Node под glibc.
- **Android**: нужны AGP ≥ 9.4 и compileSdk 37 (иначе jlink `JdkImageTransform` падает на JDK 27, а Compose 1.12 требует compileSdk 37).
- Шифрование реализовано (`RcloneCipher`, совместим с rclone crypt, чистый Kotlin в `core`; единственный `expect/actual` — источник случайности). Реальной синхронизации и OAuth ещё нет: заглушки бросают `NotImplementedError` с осмысленным сообщением. Пароль в `RcloneCipher` передаётся в открытом виде; golden-векторы `RcloneVectors.kt` генерирует `scripts/gen-rclone-vectors.sh` (нужен rclone), обычные сборка и тесты rclone не требуют.
- **Google OAuth-клиенты**: две пары — `MIMIR_GOOGLE_DESKTOP_CLIENT_ID/SECRET` (вход через браузер) и `MIMIR_GOOGLE_DEVICE_CLIENT_ID/SECRET` (Device Code); старые `MIMIR_GOOGLE_CLIENT_*` — алиас Device-пары. Вшитые значения (`MIMIR_BUILD_GOOGLE_*`) живут только в BuildConfig `daemon` (не в `core`: он попадает в JS-бандлы), в Docker-образ не вшиваются — Docker-демон настраивается переменными окружения.
- **Linux-лаунчеры** (`run` в `app-desktop/build.gradle.kts`, `packaging/arch/mimir.sh`) выставляют `_JAVA_AWT_WM_NONREPARENTING=1` — иначе под niri окно рисуется только в левом верхнем углу.
- `SyncStatus` включает `Offline` (нет сети); прогресс — `SyncEngine.progress`. Ошибка одного файла не прерывает синхронизацию: она попадает в проблемы и повторяется в следующем прогоне.
- Защита от удаления: при пустом удалённом списке и непустом индексе движок прерывает прогон (`SyncAbortedException`), ничего не трогая; каждый `DeleteLocal` перед выполнением перепроверяется через `stat` (файл найден или проверка не удалась — локальный файл не удаляется). `GoogleDriveStorage.listAll` сбрасывает кэши путей, чтобы `stat` не был устаревшим.
- Строка статуса до подключения — ровно `Mimir: not connected` (`SyncStatus.label()`).
- Локальный HTTP API: `127.0.0.1:47821`, адрес/порт настраиваются; занятый порт → сообщение `Port <port> on <bind> is already in use`, код 2, без стектрейса.
- Часы — `kotlin.time.Clock`; отдельного порта `Clock` нет.
- Платформы: Linux — Arch + системная Java (`java-runtime>=27`); Windows — MSI per-user с вшитой JRE; Android — APK вне Play, `MANAGE_EXTERNAL_STORAGE`; браузер — только Chromium, MV3; плагин Obsidian не `isDesktopOnly` и не использует ничего, кроме модуля `obsidian`.

## Процесс

- TDD: сначала падающий тест, потом код. Тесты в `commonTest` для `core`/`drive`, `desktopTest` для `ui`.
- Шифрование путей — **по сегментам** (`a/b/c.md` → три зашифрованных сегмента).
- Коммиты — conventional-стиль, как в плане (`feat(core): …`, `chore: …`, `ci: …`).
- Прогресс выполнения плана ведётся в `.superpowers/sdd/<план>/progress.md` (git-ignored); отклонения от плана записываются как `Ruling:`.

## Релизы

- Версионирование — changesets (`.changeset/`, `package.json` в каждом модуле только как носитель версии). Все модули в группе `fixed`: версия одна, `pnpm run version` синхронизирует её в `mimir.version` (`gradle.properties`); `pnpm run version:check` проверяет рассинхрон.
- Изменение, влияющее на релиз, сопровождается `pnpm changeset`.
- Поток: push в `main` → `changesets.yml` ведёт PR «chore: version packages» → после слияния `scripts/tag-release.mjs` ставит тег `v<версия>` и вызывает `release.yml` (matrix-сборка msi/apk/zip, Arch в контейнере, Docker в GHCR, публикация в GitHub Release).
- Версию в `gradle.properties` руками не менять — только через changesets.

## Проектные скиллы (`.claude/skills/`)

- `mimir-build-verify` — как собрать и проверить изменения на Arch, что делать без Android SDK.
- `mimir-add-module` — добавить новый Gradle-модуль по конвенциям проекта.
- `mimir-storage-adapter` — добавить адаптер облачного хранилища (порт `RemoteStorageProvider`).
- `mimir-identifiers` — добавить/изменить долгоживущий идентификатор без дублирования.
