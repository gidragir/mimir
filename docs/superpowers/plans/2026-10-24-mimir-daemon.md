# Mimir Daemon Engine, Local Vault & HTTP API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать реальное локальное хранилище заметок (`FileSystemLocalVault` с атомарной записью и `VaultWatcher`), фоновый оркестратор (`DaemonRuntime` & `SyncScheduler` с поддержкой Change Feed и подавлением эхо) и локальный Ktor HTTP API v1 (`/api/v1/*`, Device Code Grant, статика Web UI, код 2 при занятом порте).

**Architecture:**
- В `core`: `FileSystemLocalVault` поверх `kotlinx.io.files` с фильтрацией системных путей по `SyncRules` и атомарной записью во временные файлы; реактивный монитор `VaultWatcher` (`NioWatchServiceVaultWatcher` в JVM с debounce 1000 мс и `PollingVaultWatcher` в common).
- В `daemon`: `DaemonConfig` с поддержкой `mimir.json` и переменных окружения, `FileTokenStorage` (права 0600), `JsonSyncStateStore` (индекс синхронизации); `DaemonRuntime` оркестрирует `DefaultSyncEngine`, `EncryptedRemoteStorage(GoogleDriveStorage)` и `SyncScheduler` (таймер 30 с + опрос Change Feed + защита Mutex); Ktor Server CIO обслуживает `/api/v1/*`, статику `:web-ui` и производит раннюю проверку порта перед биндом.

**Tech Stack:** Kotlin 2.4.21 KMP (jvm, android, js, wasmJs), kotlinx-io (`Path`, `SystemFileSystem`, `Source`, `Sink`), kotlinx-coroutines (`Flow`, `Mutex`, `debounce`), kotlinx-datetime, Ktor Server CIO 3.6.0, Ktor Server Content Negotiation & JSON, Ktor Server Test Host.

**Spec:** `docs/superpowers/specs/2026-10-24-mimir-daemon-design.md` (родительская: `docs/superpowers/specs/2026-10-23-mimir-design.md`, §6.1, §6.2, §6.2a, §6.3, §6.4, §6.5, §6.7).

---

## Global Constraints

- Версии: Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8.
- Зависимости идут строго вниз: `daemon` -> `drive` -> `core`. `core` не зависит от `drive`, внешних HTTP-серверов или платформ.
- Запрет на SQLite: состояние персистится строго в JSON/файлах с атомарной заменой.
- Порт по умолчанию: 47821 (`BuildConfig.API_DEFAULT_PORT` из `gradle.properties`). При занятом порте — завершение процесса с кодом 2 и диагностическим сообщением без стектрейса.
- Префикс команд: строго `rtk` (например, `rtk mise x java@temurin-27 -- ./gradlew :core:jvmTest`).
- TDD: сначала падающий тест, затем реализация.
- Коммиты: conventional commits (`feat(core): …`, `feat(daemon): …`, `test(daemon): …`).
- Версию в `gradle.properties` вручную не менять; изменения сопровождаются `pnpm changeset`.

---

## Review Focus

1. Атомарность локальной записи: внезапное прерывание записи не оставляет битых или частично записанных заметок в vault (проверяется в Task 1).
2. Подавление эхо-циклов: запись файла движком синхронизации из облака не должна вызывать ответную реакцию watcher'а и повторный upload (проверяется в Task 2 и Task 4).
3. Многопоточная безопасность и защита от гонок: одновременный вызов `POST /api/v1/sync` и таймера планировщика сериализуется через `Mutex` и не приводит к конфликту `SyncIndex` (проверяется в Task 4 и Task 5).
4. Корректность кодов ошибок HTTP API: при занятом порте код 2 без стектрейса; при неконфигурированном vault возврат `412 Precondition Failed`; при отсутствии токена — `401 Unauthorized` (проверяется в Task 5).
5. Разрешение конфликтов без потери данных: эндпоинт `/api/v1/conflicts/resolve` корректно применяет выбранную версию и очищает конфликтный дубликат на диске (проверяется в Task 6).

---

### Task 1: `FileSystemLocalVault` в `core`

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt`
- Test: `core/src/jvmTest/kotlin/dev/mimir/core/fs/FileSystemLocalVaultTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  package dev.mimir.core.fs

  import dev.mimir.core.LocalEntry
  import dev.mimir.core.LocalVault
  import dev.mimir.core.VaultChange
  import dev.mimir.core.VaultFileId
  import dev.mimir.core.rules.SyncRules
  import kotlinx.coroutines.flow.Flow
  import kotlinx.io.Source
  import kotlinx.io.files.Path

  class FileSystemLocalVault(
      val rootPath: Path,
      val rules: SyncRules = SyncRules(),
      private val watcher: VaultWatcher? = null,
  ) : LocalVault
  ```

- [ ] **Step 1: Write failing tests in `FileSystemLocalVaultTest`**
  - Проверка `list()`: рекурсивный обход тестового каталога, игнорирование `.git/**`, `.obsidian/workspace*.json`, `.trash/**`, файлов временного сохранения `.mimir_tmp_*`.
  - Проверка `read()` и `write()` по относительному пути: запись текстового содержимого, проверка его чтения, проверка появления метаданных `LocalEntry` (размер, `modifiedAt`).
  - Проверка атомарности: запись осуществляется через временный файл `.mimir_tmp_*` в той же папке с последующим перемещением.
  - Проверка `deleteByPath()` и `delete(VaultFileId)`.
- [ ] **Step 2: Run test to verify it fails**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*FileSystemLocalVaultTest*'`
  - Expected: FAIL (Class or functions not found).
- [ ] **Step 3: Implement `FileSystemLocalVault`**
  - Использовать `kotlinx.io.files.SystemFileSystem` и `kotlinx.io.files.Path`.
  - Нормализация путей с прямыми слешами `/`.
  - Генерация стабильного `VaultFileId("fs:${relativePath}")`.
  - Реализация безопасной записи: создание `.mimir_tmp_${randomUUID()}`, `sink.write(...)`, закрытие, `SystemFileSystem.atomicMove(...)`.
- [ ] **Step 4: Run test to verify it passes**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*FileSystemLocalVaultTest*'`
  - Expected: PASS.
- [ ] **Step 5: Commit**
  - `rtk git add core/`
  - `rtk git commit -m "feat(core): implement FileSystemLocalVault with atomic writes and sync rules"`

---

### Task 2: `VaultWatcher` (JVM WatchService и Polling Fallback)

**Files:**
- Create: `core/src/commonMain/kotlin/dev/mimir/core/fs/VaultWatcher.kt`
- Create: `core/src/commonMain/kotlin/dev/mimir/core/fs/PollingVaultWatcher.kt`
- Create: `core/src/jvmMain/kotlin/dev/mimir/core/fs/NioWatchServiceVaultWatcher.kt`
- Test: `core/src/jvmTest/kotlin/dev/mimir/core/fs/VaultWatcherTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  package dev.mimir.core.fs

  import dev.mimir.core.VaultChange
  import kotlinx.coroutines.flow.Flow

  interface VaultWatcher {
      fun changes(): Flow<VaultChange>
      fun markWrittenBySync(relativePath: String)
      fun close()
  }
  ```

- [ ] **Step 1: Write failing tests in `VaultWatcherTest`**
  - Тест генерации событий при создании, модификации и удалении файла в отслеживаемом каталоге.
  - Тест подавления дребезга (debounce): 5 быстрых модификаций одного файла приводят к одному событию `VaultChange.Modified`.
  - Тест подавления эхо (`markWrittenBySync`): файл, помеченный как записанный движком, не генерирует повторного исходящего события в течение TTL окна.
- [ ] **Step 2: Run test to verify it fails**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*VaultWatcherTest*'`
  - Expected: FAIL.
- [ ] **Step 3: Implement `VaultWatcher`**
  - Реализовать `NioWatchServiceVaultWatcher` в `core/jvmMain` с рекурсивной регистрацией директорий и динамической подпиской на вновь созданные подпапки.
  - Реализовать корутинный буфер с `debounce(1000)` и реестр `recentSyncWrites: ConcurrentHashMap<String, Instant>` с очисткой по TTL (2000 мс).
  - Реализовать `PollingVaultWatcher` в `commonMain` на основе периодического сравнения `Map<String, Pair<Long, Instant>>`.
  - Связать `FileSystemLocalVault.changes()` с переданным `VaultWatcher`.
- [ ] **Step 4: Run test to verify it passes**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests '*VaultWatcherTest*'`
  - Expected: PASS.
- [ ] **Step 5: Commit**
  - `rtk git add core/`
  - `rtk git commit -m "feat(core): add NioWatchServiceVaultWatcher with debouncing and echo suppression"`

---

### Task 3: Персистентная конфигурация, токены и состояние в модуле `daemon`

**Files:**
- Modify: `daemon/build.gradle.kts` (добавить зависимость `implementation(project(":drive"))`)
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt`
- Create: `daemon/src/main/kotlin/dev/mimir/daemon/FileTokenStorage.kt`
- Create: `daemon/src/main/kotlin/dev/mimir/daemon/JsonSyncStateStore.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/DaemonConfigTest.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/FileTokenStorageTest.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/JsonSyncStateStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  package dev.mimir.daemon

  import dev.mimir.core.sync.SyncIndex
  import dev.mimir.core.sync.SyncStateStore
  import dev.mimir.drive.auth.OAuthTokens
  import dev.mimir.drive.auth.TokenStorage
  import java.nio.file.Path

  class FileTokenStorage(val tokensFile: Path) : TokenStorage
  class JsonSyncStateStore(val indexFile: Path) : SyncStateStore
  ```

- [ ] **Step 1: Write failing tests**
  - `DaemonConfigTest`: чтение из `mimir.json`, переопределение переменными окружения (`MIMIR_PORT`, `MIMIR_BIND`, `MIMIR_VAULT`, `MIMIR_TOKEN`, `MIMIR_PASSWORD`, `MIMIR_PASSWORD_FILE`, `MIMIR_GOOGLE_CLIENT_ID`, `MIMIR_GOOGLE_CLIENT_SECRET`).
  - `FileTokenStorageTest`: сохранение `OAuthTokens`, чтение, проверка прав доступа `rw-------` (POSIX 600).
  - `JsonSyncStateStoreTest`: сохранение `SyncIndex`, восстановление после перезапуска, атомарная перезапись файла `index.json`.
- [ ] **Step 2: Run test to verify it fails**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test --tests '*ConfigTest*' --tests '*TokenStorageTest*' --tests '*StateStoreTest*'`
  - Expected: FAIL.
- [ ] **Step 3: Implement storage and configuration classes**
  - Обновить `daemon/build.gradle.kts`: подключить `project(":drive")`.
  - Расширить `DaemonConfig` поддержкой полей пароля, Google OAuth client ID/secret, пути к конфиг-директории и метода `DaemonConfig.load(configDir, env)`.
  - Реализовать `FileTokenStorage` поверх `java.nio.file.Files` с `PosixFilePermissions.fromString("rw-------")` на POSIX-системах.
  - Реализовать `JsonSyncStateStore` с JSON-сериализацией и атомарной перезаписью через временный файл.
- [ ] **Step 4: Run test to verify it passes**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test`
  - Expected: PASS.
- [ ] **Step 5: Commit**
  - `rtk git add daemon/`
  - `rtk git commit -m "feat(daemon): persistent config, token storage with 0600 permissions and index state store"`

---

### Task 4: `SyncScheduler` и оркестратор `DaemonRuntime`

**Files:**
- Create: `daemon/src/main/kotlin/dev/mimir/daemon/SyncScheduler.kt`
- Create: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/SyncSchedulerTest.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/DaemonRuntimeTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  package dev.mimir.daemon

  import dev.mimir.core.SyncEngine
  import dev.mimir.core.SyncStatus
  import kotlinx.coroutines.flow.StateFlow
  import kotlin.time.Duration
  import kotlin.time.Instant

  class SyncScheduler(
      val syncEngine: SyncEngine,
      val syncInterval: Duration,
      val onTrigger: suspend () -> Unit,
  ) {
      fun start()
      fun stop()
      suspend fun triggerSync(): Boolean
      val lastSyncTime: Instant?
  }

  class DaemonRuntime(val config: DaemonConfig) {
      val status: StateFlow<SyncStatus>
      suspend fun initialize()
      suspend fun triggerSync(): Boolean
      suspend fun unlock(password: String)
      fun getConflicts(): List<ConflictFileEntry>
      suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)
  }
  ```

- [ ] **Step 1: Write failing tests**
  - `SyncSchedulerTest`: проверка сериализации через `Mutex` (при вызове `triggerSync()` во время уже идущей синхронизации повторный запуск отклоняется с `false`); проверка периодического тика таймера.
  - `DaemonRuntimeTest`: инициализация при отсутствии токенов/пути переводит статус в `Mimir: not connected`; при передаче пароля разблокируется шифрование; корректная реакция на ручной вызов `triggerSync()`.
- [ ] **Step 2: Run test to verify it fails**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test --tests '*SyncSchedulerTest*' --tests '*DaemonRuntimeTest*'`
  - Expected: FAIL.
- [ ] **Step 3: Implement `SyncScheduler` and `DaemonRuntime`**
  - В `SyncScheduler`: корутинный цикл с `delay(syncInterval)`, корутина подписки на локальные изменения `localVault.changes()` с debounce, метод `triggerSync()` с `syncMutex.tryLock()`.
  - В `DaemonRuntime`: сборка графа зависимостей (`GoogleAuthClient`, `FileTokenStorage`, `RcloneCipher`, `EncryptedRemoteStorage(GoogleDriveStorage)`, `FileSystemLocalVault`, `DefaultSyncEngine`, `JsonSyncStateStore`).
- [ ] **Step 4: Run test to verify it passes**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test --tests '*SyncSchedulerTest*' --tests '*DaemonRuntimeTest*'`
  - Expected: PASS.
- [ ] **Step 5: Commit**
  - `rtk git add daemon/`
  - `rtk git commit -m "feat(daemon): background SyncScheduler and DaemonRuntime orchestrator"`

---

### Task 5: HTTP API v1: Статус, Синхронизация, Разблокировка и Аутентификация

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Main.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/RoutesTest.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/PortInUseTest.kt`

**Interfaces:**
- Produces:
  - Маршруты: `GET /health`, `GET /api/v1/status`, `POST /api/v1/sync`, `POST /api/v1/unlock`.
  - Плагин проверки токена: при настроенном `DaemonConfig.token` проверка `Authorization: Bearer <token>` или `X-Mimir-Token: <token>`, возврат `401 Unauthorized` при несовпадении.
  - Коды возврата: `/api/v1/sync` возвращает `202 Accepted` при старте, `409 Conflict` если синхронизация уже выполняется, `412 Precondition Failed` если сервис не сконфигурирован.

- [ ] **Step 1: Write failing tests in `RoutesTest`**
  - Тест `/health`: `200 OK`, `status == "ok"`, `version == BuildConfig.VERSION`.
  - Тест токена аутентификации: настроить сервер с токеном `secret123`, проверить отказ `401` без заголовка, успех `200` с `Authorization: Bearer secret123` и с `X-Mimir-Token: secret123`.
  - Тест `/api/v1/status`: возвращает JSON со статусом, меткой (`Mimir: not connected` / `Mimir: idle`), `lastSyncAt`, `isLocked`.
  - Тест `/api/v1/sync`: возврат `202`, `409` при конкурентном запуске, `412` при отсутствии конфигурации.
  - Тест `/api/v1/unlock`: отправка пароля, успешный ответ `200 {"status":"unlocked"}`.
- [ ] **Step 2: Run test to verify it fails**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test --tests '*RoutesTest*'`
  - Expected: FAIL.
- [ ] **Step 3: Implement routes and auth interceptor in `Routes.kt`**
  - Добавить проверку токена через Ktor `createApplicationPlugin` или перехватчик `onCall`.
  - Добавить эндпоинты `/api/v1/status`, `/api/v1/sync`, `/api/v1/unlock`.
  - Обновить `Main.kt` для запуска Ktor с реальным `DaemonRuntime`.
- [ ] **Step 4: Run test to verify it passes**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test`
  - Expected: PASS.
- [ ] **Step 5: Commit**
  - `rtk git add daemon/`
  - `rtk git commit -m "feat(daemon): HTTP API v1 status, sync, unlock and token auth"`

---

### Task 6: HTTP API v1: Конфликты и Device Code Grant

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/ConflictAndDeviceAuthRoutesTest.kt`

**Interfaces:**
- Produces:
  - Эндпоинты:
    - `GET /api/v1/conflicts`
    - `POST /api/v1/conflicts/resolve`
    - `POST /api/v1/auth/device/start`
    - `GET /api/v1/auth/device/poll?device_code=...`

- [ ] **Step 1: Write failing tests in `ConflictAndDeviceAuthRoutesTest`**
  - Тест обнаружения конфликтов: положить в mock-хранилище файл `Notes/Doc (conflicted copy 2026-10-24-120000).md`, вызвать `GET /api/v1/conflicts`, проверить возвращенный список.
  - Тест разрешения конфликта `keep_original`: удаление конфликтного файла, подтверждение `200 OK`.
  - Тест разрешения конфликта `keep_conflict`: перезапись оригинального файла содержимым конфликтного и удаление конфликтного файла, `200 OK`.
  - Тест Device Code: `POST /api/v1/auth/device/start` возвращает `user_code` и `verification_url`; `GET /api/v1/auth/device/poll` возвращает `pending`, а при подтверждении — `authorized` и сохраняет токен в `FileTokenStorage`.
- [ ] **Step 2: Run test to verify it fails**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test --tests '*ConflictAndDeviceAuthRoutesTest*'`
  - Expected: FAIL.
- [ ] **Step 3: Implement conflict and device auth endpoints**
  - В `Routes.kt` реализовать роуты конфликтов через методы `DaemonRuntime` (`getConflicts()`, `resolveConflict()`).
  - Реализовать роуты Device Flow через вызовы `GoogleAuthClient.requestDeviceCode()` и `GoogleAuthClient.pollDeviceToken()`.
- [ ] **Step 4: Run test to verify it passes**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test --tests '*ConflictAndDeviceAuthRoutesTest*'`
  - Expected: PASS.
- [ ] **Step 5: Commit**
  - `rtk git add daemon/`
  - `rtk git commit -m "feat(daemon): HTTP API conflict resolution and Device Code Grant endpoints"`

---

### Task 7: Сквозное интеграционное тестирование полного цикла (E2E)

**Files:**
- Create: `daemon/src/test/kotlin/dev/mimir/daemon/DaemonFullCycleE2ETest.kt`

- [ ] **Step 1: Write failing E2E test**
  - Создание временного каталога локального vault.
  - Подключение `EncryptedRemoteStorage` поверх in-memory эмулятора Google Drive с Change Feed.
  - Проверка цикла 1: локальное создание `Notes/Idea.md` -> триггер watcher -> запуск `triggerSync()` -> появление зашифрованных блоков в удаленном хранилище.
  - Проверка цикла 2: удаленное добавление зашифрованного файла в облако -> эмуляция Change Feed -> запуск синхронизации -> появление расшифрованного файла на локальном диске в каталоге vault.
  - Проверка цикла 3: вызов `POST /api/v1/sync` через Ktor Test Host, получение `202 Accepted`, проверка обновления `lastSyncAt` в `GET /api/v1/status`.
- [ ] **Step 2: Run test to verify it fails**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew :daemon:test --tests '*DaemonFullCycleE2ETest*'`
  - Expected: FAIL (или проверка прохождения граничных сценариев).
- [ ] **Step 3: Refine and complete E2E integration**
  - Исправление обнаруженных краевых случаев взаимодействия между `FileSystemLocalVault`, `SyncScheduler` и HTTP сервером.
- [ ] **Step 4: Run all tests across the project to verify full pass**
  - Run: `rtk mise x java@temurin-27 -- ./gradlew test`
  - Expected: PASS для всех модулей (`core`, `drive`, `daemon`, `ui`).
- [ ] **Step 5: Commit and record changeset**
  - `rtk git add .`
  - `rtk git commit -m "test(daemon): add full-cycle E2E integration tests"`
