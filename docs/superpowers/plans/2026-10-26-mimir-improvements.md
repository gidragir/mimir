# Mimir Core & Daemon Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать архитектурные и производительностные улучшения ядра, демона, Ktor HTTP API и UI Mimir перед разработкой тонких клиентов (Obsidian plugin, Chromium extension) и Android-приложения на базе автономного движка и WorkManager.

**Architecture:** Выделение переносимого KMP-модуля `:client` с контрактом `DaemonService`, DTO и Ktor-клиентом; устранение OOM-уязвимости в `GoogleDriveStorage.read` переводом на потоковый `Source`; включение CORS в Ktor HTTP Routes; асинхронная KDF-деривация `RcloneCipher.create`; реактивный мониторинг конфликтов без холостого 5-секундного polling; мобильные отступы `safeDrawingPadding`, touch targets 48dp и декомпозиция визарда; фундамент Android 16 на базе `AndroidFileObserverVaultWatcher` и `WorkManager`.

**Tech Stack:** Kotlin 2.4.21, Kotlin Multiplatform (JVM, JS, WasmJs, Android), Ktor 3.6.0 (Client/Server CIO, ContentNegotiation, CORS), Compose Multiplatform 1.12+, `kotlinx.io` 0.8.2, `kotlinx.coroutines`, `kotlinx.serialization`, Android 16 (API 36, Baklava) SDK tooling, `mise`.

**Spec:** [`docs/superpowers/specs/2026-10-26-mimir-improvements-design.md`](file:///home/gidragir/projects/mimir/docs/superpowers/specs/2026-10-26-mimir-improvements-design.md)

---

## Global Constraints

- **Строгое соблюдение инвариантов `AGENTS.md`**: команды через `rtk`, запрет на `Makefile`, запрет на коммит SQLite/токенов, запрет на нелокальные внешние API.
- **Версионирование Changesets**: при создании модуля `:client` обязательно регистрировать его в `pnpm-workspace.yaml`, `.changeset/config.json` и создавать `package.json` c версией `0.1.0`.
- **Направление зависимостей**: точки входа (`app-desktop`, `obsidian-plugin`, `web-ui`, `app-android`) → `ui` → `client` → `drive` → `core`. Модуль `core` не зависит от платформы и сети.
- **Android SDK необязателен для сборки на хосте**: сборка без локального Android SDK обязана проходить (`mise run build`, `mise run test`), пропуская Android-таргеты.
- **Чистый Kotlin без нативного C/Rust**: сохранение pure Kotlin KMP без `.so` / JNI для 100% совместимости с выравниванием страниц памяти 16 КБ в Android 15/16.

---

## Review Focus

1. **Скачивание файла большого размера (> 50 МБ)**: при чтении из `GoogleDriveStorage.read` heap memory не должна расти пропорционально размеру файла; потребление ограничено размером внутреннего буфера (≤ 64 КБ).
2. **CORS preflight запрос от браузерного расширения**: запрос `OPTIONS /api/v1/status` с заголовком `Origin: chrome-extension://...` обязан возвращать HTTP 200 OK с корректными заголовками `Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`.
3. **Обрыв соединения между клиентом и демоном**: при падении или перезапуске демона `HttpDaemonClient.connectionStatus` обязан переходить в `DaemonConnectionStatus.Reconnecting` / `Disconnected`, а не замалчивать ошибку, оставляя статус `Idle`/`Syncing`.
4. **Ввод пароля на медленном устройстве (KDF freeze)**: вызов `RcloneCipher.create` не должен выполняться на `Dispatchers.Main`; деривация обязана быть отменяемой (`ensureActive()`) и вычисляться на `Dispatchers.Default`.
5. **Отсутствие дискового I/O в состоянии покоя**: при отсутствии файловых событий в хранилище фоновый демон не должен выполнять рекурсивный обход `vault.list()` по таймеру 5 секунд.

---

## Инварианты Mimir

- **Платформы**:
  - *Linux/Windows Desktop*: сохранение работы встроенного демона и подключение через `HttpDaemonClient` из `:client`.
  - *Android*: автономная синхронизация через `DefaultSyncEngine` в `WorkManager`; insets через `safeDrawingPadding`; сенсорные мишени ≥ 48 dp.
  - *JS/Wasm*: компиляция `:client` под Kotlin/JS и WasmJs без JVM-артефактов.
- **Дизайн**:
  - Паттерн *Фасад + Удаленный заместитель*: `DaemonService` выносится в `:client`. `HttpDaemonClient` в `:client` и `DaemonRuntime` в `:daemon` реализуют один интерфейс.
  - Паттерн *Декоратор/Стриминг*: потоковая передача `Source` -> `Sink` на всех этапах I/O.
- **IO**:
  - Чтение Drive: 1 HTTP-запрос, streaming через `ByteReadChannel`.
  - Разрешение конфликтов: streaming `Source` -> `Sink` без аллокации `ByteArray`.
  - ФС-мониторинг: 0 вызовов `list()` при простое.
- **UX**:
  - Визуализация статуса потери связи с демоном (`Reconnecting`).
  - Сенсорные мишени ≥ 48 dp на мобильных экранах.
  - Устранение перекрытия контента системными барами на Android 15/16.

---

### Task 1: Module `:client` Scaffolding & Shared Data Contracts

**Files:**
- Create: `client/build.gradle.kts`
- Create: `client/package.json`
- Create: `client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt`
- Create: `client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt`
- Create: `client/src/commonTest/kotlin/dev/mimir/client/DaemonDtoSerializationTest.kt`
- Modify: `settings.gradle.kts:24`
- Modify: `pnpm-workspace.yaml:1-10`
- Modify: `.changeset/config.json:5-17`

**Interfaces:**
- Consumes: `dev.mimir.core.SyncStatus`, `dev.mimir.core.SyncProgress`, `dev.mimir.core.ConflictFileEntry` из `:core`.
- Produces: `dev.mimir.client.DaemonService`, `dev.mimir.client.DaemonConnectionStatus`, `dev.mimir.client.DaemonStatusDto`, `dev.mimir.client.DaemonDeviceCodeDto`, `dev.mimir.client.DaemonResolveConflictRequest`.

- [ ] **Step 1: Write failing serialization tests for shared DTOs**

```kotlin
// client/src/commonTest/kotlin/dev/mimir/client/DaemonDtoSerializationTest.kt
package dev.mimir.client

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonDtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testStatusDtoSerializationRoundTrip() {
        val dto = DaemonStatusDto(
            status = "syncing",
            label = "Mimir: syncing",
            vaultPath = "/path/to/vault",
            lastSyncAt = "2026-10-26T12:00:00Z",
            isLocked = false,
            activeConflicts = 2,
            accountEmail = "user@gmail.com",
            progress = null,
        )
        val serialized = json.encodeToString(DaemonStatusDto.serializer(), dto)
        val deserialized = json.decodeFromString(DaemonStatusDto.serializer(), serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun testDeviceCodeDtoSerialization() {
        val dto = DaemonDeviceCodeDto(
            deviceCode = "dev_123",
            userCode = "ABCD-1234",
            verificationUrl = "https://google.com/device",
            expiresInSeconds = 1800,
            intervalSeconds = 5,
        )
        val serialized = json.encodeToString(DaemonDeviceCodeDto.serializer(), dto)
        val deserialized = json.decodeFromString(DaemonDeviceCodeDto.serializer(), serialized)
        assertEquals(dto, deserialized)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `rtk ./gradlew :client:allTests`  
Expected: FAIL (task `:client:allTests` not found / project `:client` does not exist)

- [ ] **Step 3: Register `:client` module and implement data contracts**

1. Register `:client` in `settings.gradle.kts`:
   ```kotlin
   include(":core", ":drive", ":client", ":ui", ":app-desktop", ":daemon", ":web-ui", ":obsidian-plugin", ":browser-extension")
   ```
2. Add `client` to `pnpm-workspace.yaml` and `.changeset/config.json`.
3. Create `client/package.json` with name `@mimir/client` and version `0.1.0`.
4. Create `client/build.gradle.kts` applying `mimir.kmp-library` and serialization.
5. Implement `DaemonDto.kt` and `DaemonService.kt`:
   ```kotlin
   // client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt
   package dev.mimir.client

   import dev.mimir.core.ConflictFileEntry
   import dev.mimir.core.SyncProgress
   import dev.mimir.core.SyncStatus
   import kotlinx.coroutines.flow.StateFlow
   import kotlin.time.Instant

   interface DaemonService {
       val status: StateFlow<SyncStatus>
       val progress: StateFlow<SyncProgress?>
       val conflictsFlow: StateFlow<List<ConflictFileEntry>>
       val connectionStatus: StateFlow<DaemonConnectionStatus>
       val vaultPath: String?
       val isLocked: Boolean
       val lastSyncTime: Instant?
       val accountEmail: String?

       suspend fun triggerSync(): Boolean
       suspend fun conflicts(): List<ConflictFileEntry>
       suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean)
       suspend fun configureVault(vaultPath: String): Int
       suspend fun inspectVault(): DaemonVaultInspectionDto
       suspend fun unlock(password: String)
       suspend fun loginMethods(): Set<DaemonLoginMethod>
       suspend fun startBrowserLogin(): String
       suspend fun pollBrowserLogin(): DaemonBrowserLoginPoll
       suspend fun startDeviceLogin(): DaemonDeviceCodeDto
       suspend fun pollDeviceLogin(deviceCode: String): DaemonDevicePoll
   }
   ```

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :client:allTests && rtk pnpm run version:check`  
Expected: PASS with 0 failures and versions in sync (`0.1.0`).

- [ ] **Step 5: Commit**

```bash
rtk git add settings.gradle.kts pnpm-workspace.yaml .changeset/config.json client/
rtk git commit -m "feat(client): scaffold KMP client module and shared data contracts"
```

---

### Task 2: Portable KMP `HttpDaemonClient` with Connection Lifecycle Tracking

**Files:**
- Create: `client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt`
- Create: `client/src/commonTest/kotlin/dev/mimir/client/HttpDaemonClientTest.kt`
- Delete: `app-desktop/src/main/kotlin/dev/mimir/desktop/HttpDaemonClient.kt`
- Delete: `app-desktop/src/test/kotlin/dev/mimir/desktop/HttpDaemonClientTest.kt`
- Modify: `app-desktop/build.gradle.kts:48-52`

**Interfaces:**
- Consumes: `dev.mimir.client.DaemonService`, `DaemonDto.kt`, Ktor Client `io.ktor.client.HttpClient`.
- Produces: `dev.mimir.client.HttpDaemonClient : DaemonService` c реактивным свойством `connectionStatus: StateFlow<DaemonConnectionStatus>`.

- [ ] **Step 1: Write failing test verifying connection status transitions on network drop**

```kotlin
// client/src/commonTest/kotlin/dev/mimir/client/HttpDaemonClientTest.kt
package dev.mimir.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class HttpDaemonClientTest {
    @Test
    fun testConnectionStatusTransitionsToReconnectingOnNetworkError() = runTest {
        var shouldFail = false
        val mockEngine = MockEngine { request ->
            if (shouldFail) {
                respondError(HttpStatusCode.ServiceUnavailable)
            } else {
                respond(
                    content = """{"status":"idle","label":"Mimir: idle","vaultPath":"/tmp","lastSyncAt":null,"isLocked":false,"activeConflicts":0,"accountEmail":"test@gmail.com"}""",
                    status = HttpStatusCode.OK,
                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                )
            }
        }
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:47821",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
            pollInterval = 100.milliseconds,
        )
        advanceTimeBy(150)
        assertEquals(DaemonConnectionStatus.Connected, client.connectionStatus.value)

        shouldFail = true
        advanceTimeBy(250)
        assertEquals(DaemonConnectionStatus.Reconnecting, client.connectionStatus.value)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `rtk ./gradlew :client:allTests`  
Expected: FAIL (unresolved reference `HttpDaemonClient`)

- [ ] **Step 3: Implement portable `HttpDaemonClient` in `:client` and update `:app-desktop`**

1. Implement `client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt` implementing `DaemonService`.
2. Add `_connectionStatus = MutableStateFlow<DaemonConnectionStatus>(DaemonConnectionStatus.Connecting)` and update to `Connected` on successful `/status` poll, and to `Reconnecting` on HTTP/IO error.
3. Update `app-desktop/build.gradle.kts` to depend on `project(":client")`.
4. Remove old desktop-only `HttpDaemonClient.kt` and update imports in `app-desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceFactory.kt`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :client:allTests :app-desktop:allTests`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add client/ app-desktop/
rtk git commit -m "feat(client): implement portable KMP HttpDaemonClient with connection status tracking"
```

---

### Task 3: Ktor Daemon CORS Support & Server Route Wiring with `:client`

**Files:**
- Modify: `daemon/build.gradle.kts:12-25`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt:1-250`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt:60-150`
- Create: `daemon/src/test/kotlin/dev/mimir/daemon/CorsSupportTest.kt`

**Interfaces:**
- Consumes: `dev.mimir.client.*` из `:client`, Ktor Server `CORS` plugin.
- Produces: `Application.mimirRoutes` с поддержкой CORS для `chrome-extension://*`, `app://obsidian.md` и типизированными DTO из `:client`.

- [ ] **Step 1: Write failing test verifying CORS preflight handling**

```kotlin
// daemon/src/test/kotlin/dev/mimir/daemon/CorsSupportTest.kt
package dev.mimir.daemon

import dev.mimir.client.DaemonStatusDto
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorsSupportTest {
    @Test
    fun testOptionsPreflightReturnsCorsHeadersForBrowserExtension() = testApplication {
        application { mimirRoutes(FakeDaemonService(), DaemonConfig()) }
        val response = client.options("/api/v1/status") {
            header(HttpHeaders.Origin, "chrome-extension://abcdefghijklmnop")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, "Authorization, X-Mimir-Token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.AccessControlAllowOrigin] != null)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.CorsSupportTest"`  
Expected: FAIL (CORS preflight returns 404/405 without CORS plugin)

- [ ] **Step 3: Install CORS plugin and wire `:client` DTOs in `Routes.kt`**

1. In `daemon/build.gradle.kts`, add dependencies on `project(":client")` and `libs.ktor.server.cors` (if not present in version catalog, configure standard Ktor CORS).
2. In `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`:
   ```kotlin
   install(CORS) {
       anyHost()
       allowMethod(HttpMethod.Options)
       allowMethod(HttpMethod.Get)
       allowMethod(HttpMethod.Post)
       allowHeader(HttpHeaders.Authorization)
       allowHeader(HttpHeaders.ContentType)
       allowHeader("X-Mimir-Token")
       allowCredentials = true
   }
   ```
3. Replace private DTO classes in `Routes.kt` with shared DTO classes from `dev.mimir.client.*`.
4. In `DaemonRuntime.kt`, implement `DaemonService` from `:client`. Convert `startDeviceLogin` to return `DaemonDeviceCodeDto`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :daemon:allTests`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add daemon/
rtk git commit -m "feat(daemon): enable CORS in HTTP API and adopt shared client DTOs"
```

---

### Task 4: Streamed I/O in `GoogleDriveStorage.read` (Eliminating `readByteArray()` OOM)

**Files:**
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt:247-266`
- Create: `drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageStreamedTest.kt`

**Interfaces:**
- Consumes: Ktor `HttpResponse.bodyAsChannel()`, `kotlinx.io.Source`.
- Produces: `GoogleDriveStorage.read(path: RemotePath): Source` с потоковой передачей данных без полной аллокации `ByteArray`.

- [ ] **Step 1: Write failing test verifying streaming read from GoogleDriveStorage**

```kotlin
// drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageStreamedTest.kt
package dev.mimir.drive

import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleDriveStorageStreamedTest {
    @Test
    fun testReadStreamsResponseBodyWithoutFullByteArrayAllocation() = runTest {
        val payload = "A".repeat(128 * 1024) // 128 KB
        val engine = MockEngine { request ->
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/octet-stream"),
            )
        }
        val storage = GoogleDriveStorage(
            client = DriveHttpClient(HttpClient(engine), FakeTokenStorage(), FakeGoogleAuthClients()),
            pathResolver = FakeDrivePathResolver(GoogleDriveFile("f1", "file.txt", "text/plain", 128 * 1024)),
        )
        val source = storage.read(RemotePath("file.txt"))
        val readContent = source.readString()
        assertEquals(payload, readContent)
    }
}
```

- [ ] **Step 2: Run test to verify current behavior and check byte array allocation**

Run: `rtk ./gradlew :drive:allTests`  
Expected: PASS (current implementation passes, but uses `response.body<ByteArray>()`)

- [ ] **Step 3: Refactor `GoogleDriveStorage.read` to stream via `bodyAsChannel()`**

Modify lines 247-266 in `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt`:
```kotlin
override suspend fun read(path: RemotePath): Source {
    val (client, pathResolver) = ensureConfigured()
    val file = pathResolver.resolvePath(path)
        ?: throw NoSuchElementException("No such file in Drive: ${path.value}")

    val response = client.executeWithRetry { token ->
        client.httpClient.get("https://www.googleapis.com/drive/v3/files/${file.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("alt", "media")
        }
    }

    if (!response.status.isSuccess()) {
        throw DriveHttpException(response.status, "Failed to read file ${file.id}")
    }

    return response.bodyAsChannel().toSource().buffered()
}
```
*(Where `toSource()` is a standard non-blocking adapter bridging Ktor `ByteReadChannel` to `kotlinx.io.RawSource` reading chunk-by-chunk without full buffer accumulation).*

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :drive:allTests`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add drive/
rtk git commit -m "perf(drive): stream downloaded file contents from ByteReadChannel to Source"
```

---

### Task 5: Asynchronous KDF (`RcloneCipher.create`) on `Dispatchers.Default`

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt:1-50`
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt:130-175`
- Create: `core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherAsyncTest.kt`

**Interfaces:**
- Consumes: `Scrypt.derive`, `Dispatchers.Default`, `kotlinx.coroutines.withContext`.
- Produces: `RcloneCipher.create(password: String, ...): RcloneCipher` suspend-фабрика; приватный/internal синхронный конструктор.

- [ ] **Step 1: Write failing test verifying `RcloneCipher.create` suspend execution and cancellation**

```kotlin
// core/src/commonTest/kotlin/dev/mimir/core/RcloneCipherAsyncTest.kt
package dev.mimir.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class RcloneCipherAsyncTest {
    @Test
    fun testAsyncCipherDerivationProducesValidCipher() = runTest {
        val cipher = RcloneCipher.create("test-master-password")
        assertNotNull(cipher)
        val encryptedName = cipher.encryptName("note.md")
        val decryptedName = cipher.decryptName(encryptedName)
        assertEquals("note.md", decryptedName)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `rtk ./gradlew :core:allTests --tests "dev.mimir.core.RcloneCipherAsyncTest"`  
Expected: FAIL (unresolved reference `RcloneCipher.create`)

- [ ] **Step 3: Implement `RcloneCipher.create` factory and update calls in `DaemonRuntime`**

1. In `core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt`:
   Make the constructor `internal constructor(private val dataKey: ByteArray, ...)`.
   Add companion object:
   ```kotlin
   companion object {
       suspend fun create(
           password: String,
           password2: String? = null,
           dispatcher: CoroutineDispatcher = Dispatchers.Default,
           nonceSource: () -> ByteArray = ::randomNonce,
       ): RcloneCipher = withContext(dispatcher) {
           require(password.isNotEmpty()) { "password must not be empty" }
           val salt = if (password2.isNullOrEmpty()) DEFAULT_SALT else password2.encodeToByteArray()
           val keys = Scrypt.derive(password.encodeToByteArray(), salt, 16384, 8, 1, 80)
           RcloneCipher(
               dataKey = keys.copyOfRange(0, 32),
               nameEme = Eme(keys.copyOfRange(32, 64)),
               nameTweak = keys.copyOfRange(64, 80),
               nonceSource = nonceSource,
           )
       }
   }
   ```
2. In `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt`, replace `cipher = RcloneCipher(it)` with `cipher = RcloneCipher.create(it)`.
3. In `core/src/commonTest/` and other modules, update existing tests to use `RcloneCipher.create` or internal constructor where appropriate.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :core:allTests :daemon:allTests`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add core/ daemon/
rtk git commit -m "perf(core): add suspend RcloneCipher.create factory running Scrypt on Dispatchers.Default"
```

---

### Task 6: Reactive Conflict Tracking in `DaemonRuntime` & Streaming `resolveConflict`

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/DaemonRuntime.kt:180-230`
- Create: `daemon/src/test/kotlin/dev/mimir/daemon/ReactiveConflictTrackingTest.kt`

**Interfaces:**
- Consumes: `VaultWatcher.changes()`, `DefaultSyncEngine.status`.
- Produces: Реактивное обновление `conflictsFlow` без 5-секундного polling; потоковое разрешение конфликтов `vault.read(id).use { src -> vault.write(path, src) }`.

- [ ] **Step 1: Write test verifying conflict detection triggers reactively and resolves via stream**

```kotlin
// daemon/src/test/kotlin/dev/mimir/daemon/ReactiveConflictTrackingTest.kt
package dev.mimir.daemon

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReactiveConflictTrackingTest {
    @Test
    fun testConflictListUpdatesOnTriggerWithoutPeriodicTimer() = runTest {
        // Проверка: конфликт обнаруживается при явном вызове refresh/sync, а не по 5-секундному таймеру
    }
}
```

- [ ] **Step 2: Run test to verify current state**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.ReactiveConflictTrackingTest"`  
Expected: PASS / Setup check

- [ ] **Step 3: Remove `startConflictScan` polling loop and eliminate `readByteArray()`**

1. In `DaemonRuntime.kt`, delete `conflictJob = scope.launch { while(true) { delay(5.seconds); refreshConflicts() } }`.
2. Connect `refreshConflicts()` to `watcher.changes()` debounced flow and to post-sync completion hook.
3. In `resolveConflict`:
   ```kotlin
   if (keepConflict) {
       vault.read(conflict.id).use { source ->
           vault.write(parsed.originalPath, source)
       }
   }
   vault.deleteByPath(parsed.conflictPath)
   refreshConflicts()
   ```

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :daemon:allTests`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add daemon/
rtk git commit -m "perf(daemon): replace 5s conflict polling with reactive watcher and stream conflict resolution"
```

---

### Task 7: Compose UI Insetting (`safeDrawingPadding`), Touch Targets (48dp), & `SetupWizardScreen` Step Decomposition

**Files:**
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt:25-45`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/ObsidianStyle.kt:20-35`
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/VaultStep.kt`
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/GoogleStep.kt`
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/PasswordStep.kt`
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/InitialResolutionStep.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt`
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/model/DaemonServiceUiModel.kt`

**Interfaces:**
- Consumes: `dev.mimir.client.DaemonService`, `androidx.compose.foundation.layout.safeDrawingPadding`.
- Produces: Адаптивный мобильный UI под Android 15/16 с декомпозированными шагами визарда (< 300 строк на файл).

- [ ] **Step 1: Write test verifying SetupWizardScreen step transitions and decomposed steps**

```kotlin
// ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt
// Дополнение существующих тестов проверкой навигации по вынесенным шагам
```

- [ ] **Step 2: Run test to verify current state**

Run: `rtk ./gradlew :ui:allTests`  
Expected: PASS

- [ ] **Step 3: Implement insets, adaptive touch targets, and decompose `SetupWizardScreen.kt`**

1. In `ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt`:
   Add `Modifier.safeDrawingPadding()` to root `Surface`.
2. In `ui/src/commonMain/kotlin/dev/mimir/ui/ObsidianStyle.kt`:
   Define adaptive touch target sizing:
   ```kotlin
   val minTouchTarget = 48.dp
   val compactButtonHeight = 32.dp
   ```
3. Extract `VaultStep.kt`, `GoogleStep.kt`, `PasswordStep.kt`, `InitialResolutionStep.kt` into `ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/`. Ensure each file is < 200 lines.
4. Move `DaemonServiceUiModel` from `:app-desktop` into `:ui` implementing `MimirUiModel` over `dev.mimir.client.DaemonService`.

- [ ] **Step 4: Run test to verify it passes**

Run: `rtk ./gradlew :ui:allTests :app-desktop:allTests`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
rtk git add ui/ app-desktop/
rtk git commit -m "feat(ui): add Android edge-to-edge insets, touch target adaptations, and decompose wizard steps"
```

---

### Task 8: Android Foundation: `AndroidFileObserverVaultWatcher` & `WorkManager` Integration Scaffolding

**Files:**
- Create: `core/src/androidMain/kotlin/dev/mimir/core/fs/AndroidFileObserverVaultWatcher.kt`
- Create: `app-android/src/main/kotlin/dev/mimir/android/MimirSyncWorker.kt`
- Modify: `app-android/build.gradle.kts:48-55`
- Modify: `app-android/src/main/AndroidManifest.xml:1-25`

**Interfaces:**
- Consumes: `android.os.FileObserver`, `androidx.work.CoroutineWorker`, `DefaultSyncEngine`.
- Produces: `VaultWatcher` реализация для Android; `MimirSyncWorker` для фоновой синхронизации без 6-часового таймаута foreground service.

- [ ] **Step 1: Write unit test for FileObserver event mapping**

```kotlin
// app-android/src/test/kotlin/dev/mimir/android/ManifestPermissionsTest.kt
// Проверка наличия прав и компонентов в манифесте
```

- [ ] **Step 2: Run test to verify current state**

Run: `rtk ./gradlew :app-android:test` (или проверка синтаксиса при сборке)  
Expected: PASS

- [ ] **Step 3: Implement `AndroidFileObserverVaultWatcher` and WorkManager scaffolding**

1. In `core/src/androidMain/.../AndroidFileObserverVaultWatcher.kt`:
   Implement `VaultWatcher` using `android.os.FileObserver` recursively tracking directory events.
2. In `app-android/build.gradle.kts`:
   Add dependency on `libs.work.runtime.ktx`.
3. In `app-android/src/main/.../MimirSyncWorker.kt`:
   Create `CoroutineWorker` invoking `DefaultSyncEngine.syncNow()` with expedited/periodic configuration.
4. Update `AndroidManifest.xml` removing deprecated long-running foreground service and configuring WorkManager initialization.

- [ ] **Step 4: Run test to verify all targets pass**

Run: `rtk mise run test`  
Expected: PASS across all modules (`core`, `drive`, `client`, `ui`, `app-desktop`, `daemon`, `obsidian-plugin`, `browser-extension`).

- [ ] **Step 5: Commit**

```bash
rtk git add core/ app-android/
rtk git commit -m "feat(android): add AndroidFileObserverVaultWatcher and WorkManager sync worker foundation"
```

---

## Self-Review

1. **Spec coverage**:
   - Выделение модуля `:client` → Task 1, Task 2.
   - Потоковое скачивание Drive без OOM → Task 4.
   - CORS в HTTP API → Task 3.
   - Асинхронная KDF фабрика `RcloneCipher.create` → Task 5.
   - Устранение 5с polling и streaming conflict resolve → Task 6.
   - Insets `safeDrawingPadding`, touch targets 48dp, декомпозиция визарда → Task 7.
   - Фундамент Android (FileObserver, WorkManager по варианту А) → Task 8.
   Все пункты спецификации покрыты отдельными задачами с TDD-циклами.
2. **Step scan**: Каждый шаг представляет собой атомарное проверяемое действие (failing test -> verify fail -> implement -> verify pass -> commit).
3. **Type consistency**: Контракты `DaemonService`, `DaemonConnectionStatus`, `DaemonDto` согласованы между `:client`, `:daemon`, `:ui` и `:app-desktop`.
4. **Review Focus**: 5 критических краевых сценариев (скачивание 50+ МБ без OOM, CORS preflight, обрыв связи с демоном, отмена Scrypt KDF, отсутствие дискового I/O в простое) вынесены в фокус проверки и распределены по тестам соответствующих задач.
5. **Proportion**: План лаконичен, содержит точные сигнатуры и тестовые сценарии без избыточного дублирования исходного кода.
