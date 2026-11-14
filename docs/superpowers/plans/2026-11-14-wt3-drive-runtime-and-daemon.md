# Рефакторинг и оптимизация :libs:drive, :libs:runtime, :libs:client и :apps:daemon (после слияния WT1 + WT2) — Implementation Plan

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ СКИЛЛ: `superpowers:using-superpowers` → `superpowers:executing-plans` → `superpowers:test-driven-development` → `superpowers:verification-before-completion`. Выполняется в изолированном воркспейсе (`Workspace: "share"` в Antigravity или `.worktrees/wt-drive-runtime-daemon` на ветке `refactor/drive-runtime-daemon`) поверх `main` с уже влитыми WT1 и WT2. Шаги отмечаются чекбоксами (`- [ ]`). Прогресс фиксировать в `.superpowers/sdd/2026-11-14-wt3-drive-runtime-and-daemon/progress.md`.

**Goal:** Декомпозировать крупные классы (`GoogleDriveStorage`, `DrivePathResolver`, `MimirRuntime`, `HttpDaemonClient`, `Routes`), устранить дублирование `InvalidPasswordException`/`SyncNotConfiguredException` и `Canary.kt` (делегируя в `CanaryVerifier` из WT2), вынести `DeviceCodeFlowRunner` и `GoogleAuthClients.fromIds`, добавить SSE-стриминг `/api/v1/events` и перевести тесты `:libs:drive`, `:libs:runtime`, `:apps:daemon` на общие `TestCiphers.forPassword`, `TestClock`, `FakeSyncEngine`, `CountingLocalVault` и `FakeUpdate*` из `:libs:core`.

**Architecture:** В `:libs:drive` скачивание выделяется в `DriveDownloader`, таблицы кэша путей — в `DrivePathCache` с пакетной регистрацией `registerPaths`, а опрос OAuth Device Code — в `DeviceCodeFlowRunner`. В `:libs:runtime` координация входа выделяется в `RuntimeAuthCoordinator`, `Canary.kt` делегирует криптопроверку в `CanaryVerifier.verifyOrInit` (`:libs:core`), а исключения сводятся к единым типам из `:libs:client`. В `:apps:daemon` и `:libs:client` добавляется поддержка Server-Sent Events (`GET /api/v1/events`).

**Tech Stack:** Kotlin Multiplatform 2.4.21, Ktor Client & Server 3.6.0 (CIO), `kotlinx-coroutines`, `kotlinx-serialization`, `kotlinx-io`.

**Spec:** `docs/superpowers/specs/2026-10-23-mimir-drive-design.md`, `docs/superpowers/specs/2026-10-24-mimir-daemon-design.md`

**Worktree / Workspace:** Antigravity `Workspace: "share"` или `.worktrees/wt-drive-runtime-daemon` (ветка `refactor/drive-runtime-daemon`)

## Global Constraints

- Строгая граница файлов: изменять **только** `libs/drive/src/**`, `libs/runtime/src/**`, `libs/client/src/**`, `apps/daemon/src/**`. Не трогать `libs/core/**`, `libs/ui/**`, `apps/desktop/**`, `apps/android/**`, `apps/obsidian-plugin/**`, `apps/browser-extension/**` и `*.gradle.kts`.
- В производственном коде запрещено использование `runBlocking` (кроме `main()` в `apps/daemon/Main.kt`).
- При запуске `./gradlew` передавать `--max-workers=4` (для защиты лимита 16 ГБ ОЗУ при параллельной работе с WT4 и WT5), а в конце всей работы выполнить `rtk ./gradlew --stop`.
- Все терминальные команды выполняются строго с префиксом `rtk`; навигация по коду — через `rtk context-init pack` и `rtk rg`.

## Инварианты Mimir

1. **Платформы:**
   - **Linux / Windows / macOS / Docker (Daemon & Desktop):** встроенный `MimirRuntime` и удалённый `HttpDaemonClient` бросают один и тот же класс `dev.mimir.client.InvalidPasswordException` и `SyncNotConfiguredException`; клиенты демона получают обновления по SSE `/api/v1/events`.
   - **Android:** использует тот же `MimirRuntime` и `GoogleDriveStorage` с батч-регистрацией путей и параллельным `inspectVault`.
   - **JS-клиенты:** получают `DeviceCodeFlowRunner` и `GoogleAuthClients.fromIds` из `:libs:drive`.
2. **Дизайн:**
   - Симметрия в `:libs:drive`: `DriveUploader` (запись) ↔ `DriveDownloader` (чтение и `Range`-скачивание) ↔ `DrivePathCache` (хранение кэша).
   - Выделение `RuntimeAuthCoordinator` из `MimirRuntime` и `AuthRoutes.kt` из `Routes.kt` удерживает размер всех классов ниже 300 строк.
   - Переиспользование эталонных тестовых двойников из `dev.mimir.core.testing` (введённых в WT2) вместо локальных копий.
3. **IO:**
   - В `GoogleDriveStorage.listAll` регистрация $N$ путей в `DrivePathResolver` выполняется за **1 захват `Mutex`** вместо $N$, обход дерева — итеративный через `ArrayDeque`.
   - В `DriveUploader.upload` метаданные загруженного файла сразу кладутся в кэш `pathResolver.registerPath(path, uploadedFile)` вместо `invalidate(path)`.
   - В `GoogleDriveStorage.getChanges` добавляется `pageSize = 1000`.
   - В `MimirRuntime.inspectVault` локальный `list()` и удалённый `listAll()` запускаются параллельно через `async`.
   - В `HttpDaemonClient` запрос `/api/v1/conflicts` отправляется только при изменении `activeConflicts` или `lastSyncAt`.
4. **UX:**
   - Ошибка неверного мастер-пароля во встроенном режиме десктопа/Android корректно перехватывается UI как `InvalidPasswordException`.

## Review Focus

1. **Единство типа `InvalidPasswordException`:** вызов `val service: DaemonService = runtime; service.unlock("wrong")` обязан ловиться блоком `catch (e: dev.mimir.client.InvalidPasswordException)`.
2. **Сохранение кэша после `DriveUploader.upload`:** после `storage.write(path, ...)` вызов `storage.stat(path)` использует закэшированный `fileId` без поискового запроса `files.list` к Drive.
3. **Десериализация `DaemonDeviceCodeDto` в snake_case и camelCase:** `Json.decodeFromString<DaemonDeviceCodeDto>` обязан принимать оба формата через `@JsonNames`.
4. **Старт `BrowserLoginReceiver` без `runBlocking`:** `BrowserLoginProvider.start()` запускает сервер и определяет порт в `suspend`-контексте.
5. **Переиспользование тестовых фейков из `:libs:core`:** все локальные копии `TestClock`, `FakeEngine`, `CountingVault`, `ScriptedUpdateHttpClient`, `FakeUpdateManager` в `:libs:drive`, `:libs:runtime`, `:apps:daemon` заменены на `dev.mimir.core.testing.*`.

## Структура файлов

```
libs/drive/src/commonMain/kotlin/dev/mimir/drive/
  GoogleDriveStorage.kt            (изм.: <250 строк, non-null deps, итеративный walk, pageSize=1000 в getChanges)
  DriveDownloader.kt               (созд.: вынос read и pumpRanged из GoogleDriveStorage)
  DriveDto.kt                      (изм.: extension GoogleDriveFile.toRemoteEntry)
  GoogleDriveStorageProvider.kt    (удал.: мёртвый код с утекающим HttpClient)
  upload/DriveUploader.kt          (изм.: registerPath вместо invalidate, общий Json)
  path/DrivePathResolver.kt        (изм.: <250 строк, единый обход родителей, registerPaths)
  path/DrivePathCache.kt           (созд.: таблицы folderCache, fileCache, idToPath)
  auth/GoogleAuthClient.kt         (изм.: GoogleAuthClients.fromIds)
  auth/DeviceCodeFlowRunner.kt     (созд.: единый цикл опроса RFC 8628 Device Code)
libs/client/src/commonMain/kotlin/dev/mimir/client/
  DaemonDto.kt                     (изм.: @JsonNames в DaemonDeviceCodeDto)
  HttpDaemonClient.kt              (изм.: <250 строк, кэш Instant, условный запрос конфликтов, подписка SSE)
  DaemonHttpTransport.kt           (созд.: вынос get/post/decodeOrThrow/toException)
libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/
  Canary.kt                        (изм.: делегирование в CanaryVerifier.verifyOrInit, typealias исключений к dev.mimir.client)
  MimirRuntime.kt                  (изм.: <270 строк, делегирование входа в RuntimeAuthCoordinator, параллельный inspectVault, cipherFactory)
  login/RuntimeAuthCoordinator.kt  (созд.: управление startLogin, pollLogin, signOut, AccountStore)
  login/BrowserLoginReceiver.kt    (изм.: suspend fun start() без runBlocking)
  login/DeviceCodeLoginProvider.kt (изм.: использование DeviceCodeFlowRunner)
apps/daemon/src/main/kotlin/dev/mimir/daemon/
  Routes.kt                        (изм.: удаление 15 typealias, SSE /api/v1/events)
  AuthRoutes.kt                    (созд.: вынос маршрутов /api/v1/auth/*)
  GoogleClients.kt                 (изм.: использование GoogleAuthClients.fromIds)
  Main.kt                          (изм.: public fun DaemonConfig.isPortFree())
```

---

### Task 1: Рефакторинг `:libs:drive` (`DriveDownloader`, `DrivePathCache`, `DriveUploader`, `DeviceCodeFlowRunner`, `GoogleAuthClients.fromIds`)

**Files:**
- Create: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/DriveDownloader.kt`
- Create: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathCache.kt`
- Create: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/DeviceCodeFlowRunner.kt`
- Modify: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt`
- Modify: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/DriveDto.kt`
- Modify: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/upload/DriveUploader.kt`
- Modify: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathResolver.kt`
- Modify: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleAuthClient.kt`
- Delete: `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorageProvider.kt`
- Delete: `libs/drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageProviderTest.kt`
- Test: `libs/drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageListAllTest.kt`, `libs/drive/src/commonTest/kotlin/dev/mimir/drive/auth/GoogleAuthClientTest.kt`

**Interfaces:**
- Produces:
  - `internal class DriveDownloader(private val client: DriveHttpClient, private val pathResolver: DrivePathResolver, private val downloadChunkBytes: Long?)`
  - `internal class DrivePathCache` + `suspend fun DrivePathResolver.registerPaths(items: List<Pair<RemotePath, GoogleDriveFile>>)`
  - `fun GoogleDriveFile.toRemoteEntry(path: RemotePath, defaultSize: Long = 0L): RemoteEntry`
  - `GoogleAuthClients.Companion.fromIds(http: HttpClient, desktopId: String?, desktopSecret: String?, deviceId: String?, deviceSecret: String?): GoogleAuthClients`
  - `enum class DevicePollStep { Pending, SlowDown, Expired, Denied }` и `fun DriveAuthException.toDevicePollStep(): DevicePollStep?` в `DeviceCodeFlowRunner.kt`

- [ ] **Step 1: Написать тесты в `GoogleDriveStorageListAllTest.kt` и `GoogleAuthClientTest.kt`**

1. В `GoogleDriveStorageListAllTest`: проверить, что после `storage.write(RemotePath("doc.md"), ...)` последующий `storage.stat(RemotePath("doc.md"))` берёт `fileId` из кэша и не делает запрос `files.list`.
2. В `GoogleAuthClientTest`: проверить `GoogleAuthClients.fromIds(...)` и `DriveAuthException.toDevicePollStep()`.
Run: `rtk ./gradlew :libs:drive:jvmTest --max-workers=4 --tests "dev.mimir.drive.GoogleDriveStorageListAllTest" --tests "dev.mimir.drive.auth.GoogleAuthClientTest"`
Expected: FAIL.

- [ ] **Step 2: Выделить `DriveDownloader`, `DrivePathCache`, `DeviceCodeFlowRunner`, обновить `GoogleDriveStorage` и `DriveUploader`, удалить `GoogleDriveStorageProvider`**

1. Вынести `toRemoteEntry` в `DriveDto.kt`; переиспользовать в `GoogleDriveStorage` и `DriveUploader`.
2. Вынести `read` и `pumpRanged` в `DriveDownloader.kt`; в `GoogleDriveStorage` сделать `client` и `pathResolver` обязательными non-null параметрами, удалить `ensureConfigured()` и `GoogleDriveStorageProvider.kt`.
3. В `GoogleDriveStorage.listAll` заменить рекурсивный `walk` на `ArrayDeque` и регистрировать пути одним вызовом `pathResolver.registerPaths(batch)`.
4. В `GoogleDriveStorage.getChanges` добавить `parameter("pageSize", 1000)` и `parameter("supportsAllDrives", "false")`.
5. В `DriveUploader.upload:75` заменить `pathResolver.invalidate(path)` на `pathResolver.registerPath(path, uploadedFile)`.
6. В `DrivePathResolver` выделить словари кэша в `DrivePathCache` и объединить `resolveRemotePathForFile` / `resolveFolderPathById`.
7. Добавить `GoogleAuthClients.fromIds` и `DeviceCodeFlowRunner.kt`.

- [ ] **Step 3: Прогнать все тесты `:libs:drive`**

Run: `rtk ./gradlew :libs:drive:allTests --max-workers=4`
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/drive/
rtk oco -y
```

---

### Task 2: Делегирование `Canary.kt` в `CanaryVerifier`, унификация исключений, выделение `RuntimeAuthCoordinator` и удаление `runBlocking` в `:libs:runtime`

**Files:**
- Modify: `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/Canary.kt`
- Modify: `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/MimirRuntime.kt`
- Create: `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/RuntimeAuthCoordinator.kt`
- Modify: `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt`
- Modify: `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/DeviceCodeLoginProvider.kt`
- Test: `libs/runtime/src/jvmTest/kotlin/dev/mimir/runtime/MimirRuntimeTest.kt`

**Interfaces:**
- Consumes: `CanaryVerifier.verifyOrInit`, `CanaryVerifier.CANARY_FILENAME`, `CanaryVerifier.computeCanaryHash` из `:libs:core` (WT2).
- Produces:
  - `typealias InvalidPasswordException = dev.mimir.client.InvalidPasswordException` и `typealias SyncNotConfiguredException = dev.mimir.client.SyncNotConfiguredException` в пакете `dev.mimir.runtime`
  - `internal class RuntimeAuthCoordinator(...)` в `dev.mimir.runtime.login`
  - `suspend fun BrowserLoginReceiver.start(): Int`
  - Параметр `cipherFactory: suspend (String) -> RcloneCipher = { RcloneCipher.create(it) }` в конструкторе `MimirRuntime`

- [ ] **Step 1: Написать тест в `MimirRuntimeTest.kt` на перехват `dev.mimir.client.InvalidPasswordException` и `dev.mimir.client.SyncNotConfiguredException` через контракт `DaemonService`**

Run: `rtk ./gradlew :libs:runtime:jvmTest --max-workers=4 --tests "dev.mimir.runtime.MimirRuntimeTest"`
Expected: FAIL.

- [ ] **Step 2: Перевести `Canary.kt` на `CanaryVerifier.verifyOrInit` и `typealias`, выделить `RuntimeAuthCoordinator`, сделать `BrowserLoginReceiver.start()` `suspend` и распараллелить `inspectVault`**

1. В `Canary.kt`: заменить `class InvalidPasswordException` и `class SyncNotConfiguredException` на `typealias` к `dev.mimir.client.*`; делегировать проверку/инициализацию в `CanaryVerifier.verifyOrInit` из `:libs:core`, оставив в `Canary.kt` только файловое чтение/запись `.canary_hash`.
2. В `BrowserLoginReceiver.kt:68`: сделать `suspend fun start(): Int` без `runBlocking`.
3. Выделить `RuntimeAuthCoordinator` из `MimirRuntime.kt`.
4. В `MimirRuntime.kt`: добавить параметр `cipherFactory: suspend (String) -> RcloneCipher = { RcloneCipher.create(it) }`, а в `inspectVault` запускать `local.list()` и `remote.listAll()` параллельно через `coroutineScope { async ... }`.
5. В `DeviceCodeLoginProvider.kt` использовать `toDevicePollStep()` из `DeviceCodeFlowRunner.kt`.

- [ ] **Step 3: Прогнать тесты `:libs:runtime:jvmTest`**

Run: `rtk ./gradlew :libs:runtime:jvmTest --max-workers=4`
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/runtime/
rtk oco -y
```

---

### Task 3: Рефакторинг `:libs:client` и `:apps:daemon` (`DaemonHttpTransport`, `@JsonNames`, `AuthRoutes`, SSE `/api/v1/events`)

**Files:**
- Modify: `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt`
- Modify: `libs/client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt`
- Create: `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonHttpTransport.kt`
- Modify: `apps/daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`
- Create: `apps/daemon/src/main/kotlin/dev/mimir/daemon/AuthRoutes.kt`
- Modify: `apps/daemon/src/main/kotlin/dev/mimir/daemon/GoogleClients.kt`
- Modify: `apps/daemon/src/main/kotlin/dev/mimir/daemon/Main.kt`
- Test: `libs/client/src/commonTest/kotlin/dev/mimir/client/HttpDaemonClientTest.kt`, `apps/daemon/src/test/kotlin/dev/mimir/daemon/RoutesTest.kt`

**Interfaces:**
- Produces:
  - `@JsonNames` на полях `DaemonDeviceCodeDto`
  - `internal class DaemonHttpTransport(...)`
  - Маршрут `GET /api/v1/events` (`text/event-stream`) в `Routes.kt`
  - `fun DaemonConfig.isPortFree(): Boolean` в `dev.mimir.daemon`

- [ ] **Step 1: Написать тесты в `HttpDaemonClientTest.kt` и `RoutesTest.kt`**

1. В `HttpDaemonClientTest`: при неизменных `activeConflicts = 1` и `lastSyncAt` между тиками клиент делает только 1 запрос `GET /api/v1/conflicts`.
2. В `RoutesTest`: `GET /api/v1/events` возвращает `Content-Type: text/event-stream` и JSON-событие статуса.
Run: `rtk ./gradlew :libs:client:jvmTest :apps:daemon:test --max-workers=4`
Expected: FAIL.

- [ ] **Step 2: Реализовать изменения в `:libs:client` и `:apps:daemon`**

1. Добавить `@JsonNames` в `DaemonDeviceCodeDto` и заменить ручной разбор `jsonObject` в `HttpDaemonClient.startDeviceLogin` на десериализацию DTO.
2. Вынести транспортные методы в `DaemonHttpTransport.kt`; кэшировать `lastSyncTime: Instant?` и опрашивать `/api/v1/conflicts` только при изменении `activeConflicts` или `lastSyncAt`.
3. В `Routes.kt` удалить 15 `private typealias`, вынести `/auth/*` в `AuthRoutes.kt` и добавить `get("/events")`.
4. В `GoogleClients.kt` использовать `GoogleAuthClients.fromIds(...)`, в `Main.kt:111` сделать `fun DaemonConfig.isPortFree(): Boolean` публичной.

- [ ] **Step 3: Прогнать тесты `:libs:client` и `:apps:daemon`**

Run: `rtk ./gradlew :libs:client:allTests :apps:daemon:test --max-workers=4`
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/client/ apps/daemon/
rtk oco -y
```

---

### Task 4: Перевод тестов `:libs:drive`, `:libs:runtime`, `:apps:daemon` на общие фейки из `dev.mimir.core.testing` и ускорение прогона

**Files:**
- Modify: `libs/drive/src/commonTest/kotlin/dev/mimir/drive/auth/GoogleAuthClientTest.kt`, `RefreshingTokenSourceTest.kt`, `libs/drive/src/commonTest/kotlin/dev/mimir/drive/client/DriveHttpClientTest.kt`
- Modify: `libs/drive/src/jvmTest/kotlin/dev/mimir/drive/client/DriveHttpClientCioTimeoutTest.kt`
- Modify: `libs/runtime/src/jvmTest/kotlin/dev/mimir/runtime/RuntimeTestSupport.kt`, `CanaryCheckTest.kt`, `MimirRuntimeTest.kt`, `MimirRuntimeModesTest.kt`, `InspectVaultTest.kt`, `EngineAssemblerTest.kt`, `IdleVaultIoTest.kt`, `SyncSchedulerTest.kt`
- Modify: `libs/runtime/src/jvmTest/kotlin/dev/mimir/runtime/update/UpdateSchedulerTest.kt`, `RuntimeUpdateIntegrationTest.kt`, `UpdateFacadeTest.kt`, `VerifiedFileDownloaderTest.kt`, `ApkInstallApplierTest.kt`, `KtorUpdateHttpClientTest.kt`
- Delete: `libs/runtime/src/jvmTest/kotlin/dev/mimir/runtime/update/ScriptedUpdateHttpClient.kt`, `apps/daemon/src/test/kotlin/dev/mimir/daemon/FakeUpdateManager.kt`
- Modify: `apps/daemon/src/test/kotlin/dev/mimir/daemon/DaemonFullCycleE2ETest.kt`, `PortInUseTest.kt`, `DockerUpdateFlowTest.kt`, `UpdateWiringTest.kt`

**Interfaces:**
- Consumes: `TestCiphers.forPassword`, `TestClock`, `FakeSyncEngine`, `CountingLocalVault`, `FakeUpdateHttpClient`, `FakeUpdateManager`, `FakeUpdateApplier` из `dev.mimir.core.testing` (WT2).

- [ ] **Step 1: Перевести `RuntimeTestSupport`, `CanaryCheckTest`, `MimirRuntime*Test`, `InspectVaultTest`, `EngineAssemblerTest`, `DaemonFullCycleE2ETest` на `TestCiphers::forPassword` и `MockEngine`**

1. В `RuntimeTestSupport.kt` передавать `cipherFactory = { TestCiphers.forPassword(it) }` и заменить дефолтный `HttpClient(CIO)` на `HttpClient(MockEngine { respondError(HttpStatusCode.NotImplemented) })`.
2. Заменить прямые вызовы `RcloneCipher.create(...)` в `CanaryCheckTest`, `InspectVaultTest`, `EngineAssemblerTest`, `DaemonFullCycleE2ETest` на `TestCiphers.forPassword(...)`.

- [ ] **Step 2: Удалить локальные дубли `TestClock`, `CountingVault`, `FakeEngine`, `ScriptedUpdateHttpClient`, `FakeUpdateManager` и сократить реальные `delay`/`Thread.sleep`**

1. Заменить локальные `TestClock`/`fixedClock`, `CountingVault`, `FakeEngine`, `ScriptedUpdateHttpClient`, `FakeUpdateManager` на классы из `dev.mimir.core.testing`.
2. Перевести `VerifiedFileDownloaderTest.kt` и `ApkInstallApplierTest.kt` с `runBlocking` на `runTest`.
3. В `IdleVaultIoTest.kt` сократить окно проверки с `2_000` до `300` мс; в `DaemonFullCycleE2ETest.kt` уменьшить `echoTtl`; в `PortInUseTest.kt:24` убрать `Thread.sleep(300)`; в `DriveHttpClientCioTimeoutTest.kt` и `KtorUpdateHttpClientTest.kt` снизить `requestTimeout` до `80` мс и шаг `Thread.sleep` до `8 × 25` мс.

- [ ] **Step 3: Прогнать все тесты, проверить дрейф `context-init` и остановить Gradle Daemon**

Run: `rtk ./gradlew :libs:drive:allTests :libs:client:allTests :libs:runtime:jvmTest :apps:daemon:test --max-workers=4 && rtk context-init check --compact && rtk ./gradlew --stop`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/drive/ libs/runtime/ apps/daemon/
rtk oco -y
```
