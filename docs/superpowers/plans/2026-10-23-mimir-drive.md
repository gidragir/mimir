# Mimir Google Drive Storage Adapter & OAuth Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать адаптер хранилища Google Drive REST API v3 (`GoogleDriveStorage`) с поддержкой потокового чтения/записи, Changes Feed и переносимой подсистемы OAuth 2.0 (Device Flow, PKCE, Token Refresh) в модуле `drive`, полностью протестированные на JVM, JS и Wasm.

**Architecture:** Чистый Kotlin KMP в `drive/commonMain`:
- Расширение портов `core`: контракт `ChangeFeedStorage` с поддержкой в `EncryptedRemoteStorage` и `InMemoryRemoteStorage`.
- Ktor HTTP Client (3.6.0) с сериализацией JSON для запросов к Google Drive REST API v3 и OAuth 2.0 эндпоинтам.
- Подсистема аутентификации: `OAuthTokens`, `GoogleAuthClient` (RFC 8628 Device Flow, PKCE code exchange), автоматический refresh токена при HTTP 401 и exponential backoff при HTTP 429.
- Иерархический резолвер путей `DrivePathResolver`: двусторонний маппинг `RemotePath <-> fileId`, навигация по дереву каталогов Google Drive, разрешение дубликатов имен.
- Потоковая передача данных: `Source`/`Sink` адаптеры, Simple upload (< 5 МБ) и Resumable upload (≥ 5 МБ) с восстановлением сессии после сетевого сбоя.
- Поддержка отслеживания удаленных изменений через Google Drive Changes API (`drive.changes.list`).

**Tech Stack:** Kotlin 2.4.21 KMP (jvm, js, wasmJs, android), Ktor Client 3.6.0 (`ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-client-mock`), kotlinx-io (`Source`, `Sink`, `Buffer`), kotlinx-coroutines (`Flow`, `Mutex`), kotlinx-datetime (`Clock`, `Instant`).

**Spec:** `docs/superpowers/specs/2026-10-23-mimir-drive-design.md` (родительская: `docs/superpowers/specs/2026-10-23-mimir-design.md`, §6.4, §6.4a).

---

## Global Constraints

- Код пишется строго в модуле `drive` (`drive/src/commonMain/kotlin/dev/mimir/drive/`), а расширение контракта — в `core` (`core/src/commonMain/kotlin/dev/mimir/core/api/`); тесты — в `commonTest`.
- Проприетарные SDK Google (`com.google.api-client` и др.) строго запрещены: только Ktor HTTP Client и Google Drive REST API v3.
- Никаких внешних секретов в репозитории: тесты используют `Ktor MockEngine`.
- Команды запускаются строго через `mise x java@temurin-27 -- ./gradlew …` с префиксом `rtk`.
- TDD: сначала падающий тест, затем реализация.
- Именование коммитов: conventional commits (`feat(drive): …`, `feat(core): …`, `test(drive): …`).
- Версию в `gradle.properties` вручную не менять; изменения сопровождаются `pnpm changeset`.

---

## Review Focus

1. Прозрачное дешифрование путей изменений `EncryptedRemoteStorage` при вызове `getChanges()` через `ChangeFeedStorage`.
2. Корректная обработка дубликатов файлов/каталогов с одинаковым именем в одной папке Google Drive (выбор самого свежего по mtime, изоляция от конфликтов).
3. Возобновление Resumable Upload чанков при обрыве HTTP-соединения (проверка смещения через `308 Resume Incomplete`).
4. Автоматический прозрачный refresh access-токена при истечении срока действия или получении ответа HTTP 401 с повтором запроса без прерывания вызывающего кода.
5. Корректная обработка HTTP 429 Too Many Requests с экспоненциальным откатом и джиттером.

---

### Task 1: Контракты Changes Feed в `core` (`ChangeFeedStorage`)

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/api/RemoteStorage.kt`
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/crypto/EncryptedRemoteStorage.kt`
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryRemoteStorage.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/ChangeFeedStorageTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class RemoteChange(
      val path: RemotePath,
      val isDeleted: Boolean,
      val entry: RemoteEntry?,
  )

  data class RemoteChangesResult(
      val changes: List<RemoteChange>,
      val nextToken: String,
  )

  interface ChangeFeedStorage : RemoteStorage {
      suspend fun getLatestChangeToken(): String
      suspend fun getChanges(sinceToken: String): RemoteChangesResult
  }
  ```

- [ ] **Step 1: Написать падающий тест для `ChangeFeedStorage` и прозрачного дешифрования в `EncryptedRemoteStorage`**

В файле `core/src/commonTest/kotlin/dev/mimir/core/ChangeFeedStorageTest.kt`:
```kotlin
package dev.mimir.core

import dev.mimir.core.testing.InMemoryRemoteStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeFeedStorageTest {
    @Test
    fun testInMemoryChangeFeedTracksModificationsAndDeletions() = runTest {
        val storage = InMemoryRemoteStorage()
        val token0 = storage.getLatestChangeToken()

        storage.write(RemotePath("folder/note.md"), kotlinx.io.Buffer().also { it.write("hello".encodeToByteArray()) })
        val changes1 = storage.getChanges(token0)
        assertEquals(1, changes1.changes.size)
        assertEquals(RemotePath("folder/note.md"), changes1.changes[0].path)
        assertEquals(false, changes1.changes[0].isDeleted)

        val token1 = changes1.nextToken
        storage.delete(RemotePath("folder/note.md"))
        val changes2 = storage.getChanges(token1)
        assertEquals(1, changes2.changes.size)
        assertEquals(RemotePath("folder/note.md"), changes2.changes[0].path)
        assertTrue(changes2.changes[0].isDeleted)
    }

    @Test
    fun testEncryptedRemoteStorageDecryptsChangesFeedPaths() = runTest {
        val rawStorage = InMemoryRemoteStorage()
        val cipher = dev.mimir.core.crypto.RcloneCipher("test-password-12345", "test-salt-67890")
        val encryptedStorage = EncryptedRemoteStorage(rawStorage, cipher)

        val token0 = (encryptedStorage as ChangeFeedStorage).getLatestChangeToken()
        encryptedStorage.write(RemotePath("secret/diary.md"), kotlinx.io.Buffer().also { it.write("secret".encodeToByteArray()) })

        val result = (encryptedStorage as ChangeFeedStorage).getChanges(token0)
        assertEquals(1, result.changes.size)
        assertEquals(RemotePath("secret/diary.md"), result.changes[0].path)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться в падении**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :core:jvmTest --tests dev.mimir.core.ChangeFeedStorageTest`  
Ожидается: Ошибка компиляции (`Unresolved reference: ChangeFeedStorage`, `RemoteChangesResult`).

- [ ] **Step 3: Реализовать `ChangeFeedStorage`, обновить `EncryptedRemoteStorage` и `InMemoryRemoteStorage`**

1. Добавить `RemoteChange`, `RemoteChangesResult` и `ChangeFeedStorage` в `core/src/commonMain/kotlin/dev/mimir/core/api/RemoteStorage.kt`.
2. Реализовать `ChangeFeedStorage` в `InMemoryRemoteStorage.kt` со счетчиком версий и фиксацией истории изменений.
3. Реализовать `ChangeFeedStorage` в `EncryptedRemoteStorage.kt` (делегирование, если `inner is ChangeFeedStorage`, с декодированием `change.path = decrypt(change.path)`).

- [ ] **Step 4: Запустить тесты и проверить успешное прохождение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :core:allTests`  
Ожидается: BUILD SUCCESSFUL, все тесты на JVM, JS и Wasm пройдены.

- [ ] **Step 5: Коммит изменений**

Команда: `rtk git add core/ && rtk oco` (или `rtk git commit -m "feat(core): add ChangeFeedStorage contract and decrypting wrapper"`)

---

### Task 2: Подключение Ktor Client зависимостей и DTO Google Drive API v3

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `drive/build.gradle.kts`
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/api/DriveDto.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/api/DriveDtoSerializationTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable
  data class GoogleDriveFile(
      val id: String,
      val name: String,
      val mimeType: String,
      val size: Long? = null,
      val modifiedTime: String? = null,
      val md5Checksum: String? = null,
      val parents: List<String> = emptyList(),
      val trashed: Boolean = false,
  ) {
      val isDirectory: Boolean get() = mimeType == "application/vnd.google-apps.folder"
  }

  @Serializable
  data class GoogleDriveFileList(
      val files: List<GoogleDriveFile> = emptyList(),
      val nextPageToken: String? = null,
  )

  @Serializable
  data class GoogleStartPageToken(val startPageToken: String)

  @Serializable
  data class GoogleDriveChange(
      val fileId: String,
      val removed: Boolean = false,
      val file: GoogleDriveFile? = null,
  )

  @Serializable
  data class GoogleDriveChangeList(
      val changes: List<GoogleDriveChange> = emptyList(),
      val newStartPageToken: String? = null,
      val nextPageToken: String? = null,
  )

  @Serializable
  data class GoogleTokenResponse(
      val access_token: String,
      val refresh_token: String? = null,
      val expires_in: Long,
      val token_type: String = "Bearer",
      val scope: String? = null,
  )

  @Serializable
  data class GoogleDeviceCodeResponse(
      val device_code: String,
      val user_code: String,
      val verification_url: String,
      val expires_in: Long,
      val interval: Long = 5,
  )
  ```

- [ ] **Step 1: Написать падающий тест на сериализацию/десериализацию DTO моделей Google Drive**

В `drive/src/commonTest/kotlin/dev/mimir/drive/api/DriveDtoSerializationTest.kt`:
```kotlin
package dev.mimir.drive.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveDtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testDeserializeFileList() {
        val payload = """
            {
                "kind": "drive#fileList",
                "nextPageToken": "token123",
                "files": [
                    {
                        "id": "file1",
                        "name": "doc.txt",
                        "mimeType": "text/plain",
                        "size": "1024",
                        "modifiedTime": "2026-10-23T12:00:00.000Z",
                        "md5Checksum": "abc123hash",
                        "parents": ["parent1"],
                        "trashed": false
                    },
                    {
                        "id": "dir1",
                        "name": "folder",
                        "mimeType": "application/vnd.google-apps.folder",
                        "trashed": false
                    }
                ]
            }
        """.trimIndent()

        val list = json.decodeFromString<GoogleDriveFileList>(payload)
        assertEquals("token123", list.nextPageToken)
        assertEquals(2, list.files.size)
        assertEquals(false, list.files[0].isDirectory)
        assertEquals(1024L, list.files[0].size)
        assertTrue(list.files[1].isDirectory)
    }

    @Test
    fun testDeserializeTokenResponse() {
        val payload = """
            {
                "access_token": "ya29.test",
                "refresh_token": "1//test_refresh",
                "expires_in": 3600,
                "token_type": "Bearer"
            }
        """.trimIndent()
        val tokens = json.decodeFromString<GoogleTokenResponse>(payload)
        assertEquals("ya29.test", tokens.access_token)
        assertEquals("1//test_refresh", tokens.refresh_token)
        assertEquals(3600L, tokens.expires_in)
    }
}
```

- [ ] **Step 2: Запустить тест и проверить падение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:jvmTest`  
Ожидается: Ошибка компиляции из-за отсутствия Ktor/сериализации и классов DTO.

- [ ] **Step 3: Обновить зависимости и реализовать `DriveDto.kt`**

1. В `gradle/libs.versions.toml` добавить:
   ```toml
   ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
   ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
   ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
   ```
2. В `drive/build.gradle.kts` добавить в `commonMain.dependencies`:
   - `implementation(libs.ktor.client.core)`
   - `implementation(libs.ktor.client.content.negotiation)`
   - `implementation(libs.ktor.serialization.json)`
   - `implementation(libs.serialization.json)`
   и в `commonTest.dependencies`:
   - `implementation(libs.ktor.client.mock)`
3. Создать `drive/src/commonMain/kotlin/dev/mimir/drive/api/DriveDto.kt` со всеми моделями.

- [ ] **Step 4: Запустить тесты и проверить успешное прохождение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:allTests`  
Ожидается: BUILD SUCCESSFUL, все тесты пройдены.

- [ ] **Step 5: Коммит изменений**

Команда: `rtk git add gradle/libs.versions.toml drive/ && rtk oco`

---

### Task 3: Модель токенов `OAuthTokens`, хранилище `TokenStorage` и клиент `GoogleAuthClient`

**Files:**
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/auth/OAuthTokens.kt`
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/auth/TokenStorage.kt`
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleAuthClient.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/auth/GoogleAuthClientTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable
  data class OAuthTokens(
      val accessToken: String,
      val refreshToken: String?,
      val expiresAtEpochSeconds: Long,
      val scope: String = "",
      val tokenType: String = "Bearer",
  ) {
      fun isExpired(clock: Clock, skewSeconds: Long = 60): Boolean
  }

  interface TokenStorage {
      suspend fun load(): OAuthTokens?
      suspend fun save(tokens: OAuthTokens)
      suspend fun clear()
  }

  class InMemoryTokenStorage(initial: OAuthTokens? = null) : TokenStorage

  class GoogleAuthClient(
      private val httpClient: HttpClient,
      private val clientId: String,
      private val clientSecret: String? = null,
      private val clock: Clock = Clock.System,
  ) {
      fun buildAuthorizationUrl(redirectUri: String, codeChallenge: String, state: String): String
      suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String, redirectUri: String): OAuthTokens
      suspend fun refreshTokens(refreshToken: String): OAuthTokens
      suspend fun requestDeviceCode(): GoogleDeviceCodeResponse
      suspend fun pollDeviceToken(deviceCode: String): OAuthTokens
      suspend fun fetchAccount(accessToken: String): dev.mimir.core.Account
  }
  ```

- [ ] **Step 1: Написать падающий тест для `GoogleAuthClient` с `MockEngine`**

В `drive/src/commonTest/kotlin/dev/mimir/drive/auth/GoogleAuthClientTest.kt`:
```kotlin
package dev.mimir.drive.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleAuthClientTest {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1700000000)
    }

    @Test
    fun testRefreshTokensSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://oauth2.googleapis.com/token", request.url.toString())
            respond(
                content = """{"access_token":"new_access","expires_in":3600,"token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(client, "test-client-id", clock = fixedClock)
        val refreshed = authClient.refreshTokens("old_refresh")
        assertEquals("new_access", refreshed.accessToken)
        assertEquals("old_refresh", refreshed.refreshToken)
        assertEquals(1700000000L + 3600L, refreshed.expiresAtEpochSeconds)
    }

    @Test
    fun testDeviceCodeFlowRequest() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("https://oauth2.googleapis.com/device/code", request.url.toString())
            respond(
                content = """
                    {
                        "device_code": "dev123",
                        "user_code": "ABCD-EFGH",
                        "verification_url": "https://www.google.com/device",
                        "expires_in": 1800,
                        "interval": 5
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(client, "test-client-id", clock = fixedClock)
        val devCode = authClient.requestDeviceCode()
        assertEquals("dev123", devCode.device_code)
        assertEquals("ABCD-EFGH", devCode.user_code)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться в падении**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:jvmTest --tests dev.mimir.drive.auth.GoogleAuthClientTest`  
Ожидается: Ошибка компиляции (классы отсутствуют).

- [ ] **Step 3: Реализовать `OAuthTokens.kt`, `TokenStorage.kt` и `GoogleAuthClient.kt`**

1. Реализовать методы построения URL авторизации, обмена кода с PKCE, обновления через refresh token, запроса Device Code и получения учетной записи пользователя из `userinfo`.
2. Реализовать `InMemoryTokenStorage` с потокобезопасным хранением через `kotlinx.coroutines.sync.Mutex`.

- [ ] **Step 4: Запустить тесты и проверить успешное прохождение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:allTests`  
Ожидается: BUILD SUCCESSFUL, тесты пройдены.

- [ ] **Step 5: Коммит изменений**

Команда: `rtk git add drive/ && rtk oco`

---

### Task 4: Резильентный HTTP-клиент, автообновление токенов (401) и Backoff (429)

**Files:**
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpClient.kt`
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpException.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/client/DriveHttpClientTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class DriveHttpClient(
      val httpClient: HttpClient,
      val tokenStorage: TokenStorage,
      val authClient: GoogleAuthClient,
      val clock: Clock = Clock.System,
  ) {
      suspend fun <T> executeWithRetry(
          maxRetries: Int = 3,
          block: suspend (token: String) -> HttpResponse,
      ): HttpResponse
  }
  ```

- [ ] **Step 1: Написать падающий тест на перехват 401 и автоматический повтор с обновленным токеном**

В `drive/src/commonTest/kotlin/dev/mimir/drive/client/DriveHttpClientTest.kt`:
```kotlin
package dev.mimir.drive.client

import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DriveHttpClientTest {
    @Test
    fun testRetriesWithRefreshedTokenOn401() = runTest {
        var callCount = 0
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath == "/oauth2/v3/token") {
                respond(
                    content = """{"access_token":"refreshed_access","expires_in":3600,"token_type":"Bearer"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                callCount++
                val authHeader = request.headers[HttpHeaders.Authorization]
                if (authHeader == "Bearer initial_access") {
                    respond("Unauthorized", status = HttpStatusCode.Unauthorized)
                } else if (authHeader == "Bearer refreshed_access") {
                    respond("""{"status":"ok"}""", status = HttpStatusCode.OK)
                } else {
                    respond("Bad token", status = HttpStatusCode.Forbidden)
                }
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(
            OAuthTokens(
                accessToken = "initial_access",
                refreshToken = "valid_refresh",
                expiresAtEpochSeconds = 2000000000L
            )
        )
        val driveClient = DriveHttpClient(http, storage, authClient)

        val response = driveClient.executeWithRetry { token ->
            http.get("https://www.googleapis.com/drive/v3/about") {
                headers[HttpHeaders.Authorization] = "Bearer $token"
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, callCount)
        assertEquals("refreshed_access", storage.load()?.accessToken)
    }
}
```

- [ ] **Step 2: Запустить тест и проверить падение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:jvmTest --tests dev.mimir.drive.client.DriveHttpClientTest`  
Ожидается: Ошибка компиляции (классы отсутствуют).

- [ ] **Step 3: Реализовать `DriveHttpClient.kt` и `DriveHttpException.kt`**

1. Реализовать синхронизированную проверку актуальности токена перед вызовом.
2. При статусе `401 Unauthorized` выполнить обновление токена по `refreshToken`, сохранить его в `tokenStorage` и повторить исходный запрос.
3. При статусе `429 Too Many Requests` или `503 Service Unavailable` выполнить экспоненциальный откат (с задержкой и джиттером) до `maxRetries` раз.

- [ ] **Step 4: Запустить тесты и проверить успешное прохождение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:allTests`  
Ожидается: BUILD SUCCESSFUL, тесты пройдены.

- [ ] **Step 5: Коммит изменений**

Команда: `rtk git add drive/ && rtk oco`

---

### Task 5: Иерархический резолвер путей Google Drive (`DrivePathResolver`)

**Files:**
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathResolver.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/path/DrivePathResolverTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class DrivePathResolver(
      private val client: DriveHttpClient,
      private val rootFolderName: String = "Mimir",
  ) {
      suspend fun resolveRootFolderId(): String
      suspend fun resolvePath(path: RemotePath): GoogleDriveFile?
      suspend fun resolveOrCreateFolderPath(dir: RemotePath): String
      suspend fun invalidate(path: RemotePath)
      suspend fun clearCache()
  }
  ```

- [ ] **Step 1: Написать падающий тест для резолвера путей и дедупликации одноименных файлов**

В `drive/src/commonTest/kotlin/dev/mimir/drive/path/DrivePathResolverTest.kt`:
```kotlin
package dev.mimir.drive.path

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DrivePathResolverTest {
    @Test
    fun testResolvesExistingNestedPathAndPicksLatestDuplicate() = runTest {
        val mockEngine = MockEngine { request ->
            val q = request.url.parameters["q"] ?: ""
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                q.contains("'root' in parents and name = 'Mimir'") -> {
                    respond("""{"files":[{"id":"root_id","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                q.contains("'root_id' in parents and name = 'folder'") -> {
                    respond("""{"files":[{"id":"folder_id","name":"folder","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                q.contains("'folder_id' in parents and name = 'note.md'") -> {
                    // Возвращаем дубликат: старый и новый
                    respond("""
                        {
                            "files": [
                                {"id":"old_file","name":"note.md","mimeType":"text/markdown","modifiedTime":"2026-10-23T10:00:00Z"},
                                {"id":"new_file","name":"note.md","mimeType":"text/markdown","modifiedTime":"2026-10-23T12:00:00Z"}
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val client = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(client, rootFolderName = "Mimir")

        val resolved = resolver.resolvePath(RemotePath("folder/note.md"))
        assertEquals("new_file", resolved?.id)
    }
}
```

- [ ] **Step 2: Запустить тест и проверить падение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:jvmTest --tests dev.mimir.drive.path.DrivePathResolverTest`  
Ожидается: Ошибка компиляции (`DrivePathResolver` отсутствует).

- [ ] **Step 3: Реализовать `DrivePathResolver.kt`**

1. Реализовать поиск и кеширование `rootFolderId`.
2. Реализовать пошаговое разрешение сегментов путей с использованием внутреннего LRU/Map-кеша.
3. Реализовать разрешение дубликатов: при наличии нескольких файлов в выдаче сортировать по `modifiedTime` убывающе и брать первый элемент.
4. Реализовать `resolveOrCreateFolderPath`: рекурсивное создание недостающих папок в цепочке.

- [ ] **Step 4: Запустить тесты и проверить успешное прохождение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:allTests`  
Ожидается: BUILD SUCCESSFUL, тесты пройдены.

- [ ] **Step 5: Коммит изменений**

Команда: `rtk git add drive/ && rtk oco`

---

### Task 6: Базовые CRUD операции в `GoogleDriveStorage` (`list`, `stat`, `read`, `delete`)

**Files:**
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageCrudTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class GoogleDriveStorage(
      private val client: DriveHttpClient,
      private val pathResolver: DrivePathResolver,
  ) : RemoteStorage
  ```

- [ ] **Step 1: Написать падающий тест для `list`, `stat`, `read` и `delete`**

В `drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageCrudTest.kt`:
```kotlin
package dev.mimir.drive

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GoogleDriveStorageCrudTest {
    @Test
    fun testListAndReadOperations() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.parameters["q"]?.contains("'mimir_root' in parents") == true -> {
                    respond("""
                        {
                            "files": [
                                {
                                    "id": "file_123",
                                    "name": "hello.txt",
                                    "mimeType": "text/plain",
                                    "size": "11",
                                    "modifiedTime": "2026-10-23T12:00:00.000Z",
                                    "md5Checksum": "hash123",
                                    "trashed": false
                                }
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/files/file_123" && request.url.parameters["alt"] == "media" -> {
                    respond("Hello World".encodeToByteArray(), HttpStatusCode.OK)
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val driveStorage = GoogleDriveStorage(driveClient, resolver)

        val entries = driveStorage.list(RemotePath(""))
        assertEquals(1, entries.size)
        assertEquals(RemotePath("hello.txt"), entries[0].path)
        assertEquals(11L, entries[0].size)

        val statEntry = driveStorage.stat(RemotePath("hello.txt"))
        assertNotNull(statEntry)
        assertEquals("hash123", statEntry.revision)

        val content = driveStorage.read(RemotePath("hello.txt")).readByteArray()
        assertEquals("Hello World", content.decodeToString())
    }
}
```

- [ ] **Step 2: Запустить тест и проверить падение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:jvmTest --tests dev.mimir.drive.GoogleDriveStorageCrudTest`  
Ожидается: FAIL с `NotImplementedError: Google Drive adapter is not implemented yet`.

- [ ] **Step 3: Реализовать `list`, `stat`, `read` и `delete` в `GoogleDriveStorage.kt`**

1. `list(dir)`: разрешить папку `dir` в `fileId`, запросить список файлов `'fileId' in parents and trashed = false` с пагинацией по `nextPageToken`.
2. `stat(path)`: вызвать `pathResolver.resolvePath(path)` и преобразовать в `RemoteEntry` (ISO-8601 `modifiedTime` -> `kotlinx.datetime.Instant`).
3. `read(path)`: получить `fileId`, сделать `GET /drive/v3/files/{fileId}?alt=media`, обернуть байты в `kotlinx.io.Buffer` или потоковый `Source`.
4. `delete(path)`: получить `fileId`, выполнить `DELETE /drive/v3/files/{fileId}` или `PATCH trashed: true`, инвалидировать кэш резолвера.

- [ ] **Step 4: Запустить тесты и проверить успешное прохождение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:allTests`  
Ожидается: BUILD SUCCESSFUL, тесты пройдены.

- [ ] **Step 5: Коммит изменений**

Команда: `rtk git add drive/ && rtk oco`

---

### Task 7: Потоковая загрузка `write` (Simple & Resumable Upload с возобновлением)

**Files:**
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/upload/DriveUploader.kt`
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/upload/DriveUploaderTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class DriveUploader(
      private val client: DriveHttpClient,
      private val pathResolver: DrivePathResolver,
      private val resumableThresholdBytes: Long = 5 * 1024 * 1024,
      private val chunkSize: Int = 1024 * 1024, // 1MB кратно 256KB
  ) {
      suspend fun upload(path: RemotePath, data: Source): RemoteEntry
  }
  ```

- [ ] **Step 1: Написать падающий тест для Simple Upload и Resumable Upload с симуляцией обрыва**

В `drive/src/commonTest/kotlin/dev/mimir/drive/upload/DriveUploaderTest.kt`:
```kotlin
package dev.mimir.drive.upload

import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DriveUploaderTest {
    @Test
    fun testSimpleUploadSmallFile() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/upload/drive/v3/files" -> {
                    respond("""
                        {
                            "id": "uploaded_small_id",
                            "name": "small.txt",
                            "mimeType": "text/plain",
                            "size": "5",
                            "modifiedTime": "2026-10-23T12:00:00.000Z",
                            "md5Checksum": "md5hash123"
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val uploader = DriveUploader(driveClient, resolver)

        val buffer = Buffer().also { it.write("Hello".encodeToByteArray()) }
        val entry = uploader.upload(RemotePath("small.txt"), buffer)
        assertEquals(RemotePath("small.txt"), entry.path)
        assertEquals(5L, entry.size)
        assertEquals("md5hash123", entry.revision)
    }

    @Test
    fun testResumableUploadWithNetworkInterruptionAndResume() = runTest {
        var chunkAttempts = 0
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.parameters["uploadType"] == "resumable" -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.Location, "https://upload.session.url/12345")
                    )
                }
                request.url.toString() == "https://upload.session.url/12345" -> {
                    val contentRange = request.headers["Content-Range"] ?: ""
                    if (contentRange == "bytes */5242880") {
                        // Запрос смещения после сбоя
                        respond("", HttpStatusCode(308, "Resume Incomplete"), headersOf("Range", "bytes=0-1048575"))
                    } else if (contentRange.startsWith("bytes 0-1048575/")) {
                        chunkAttempts++
                        respond("", HttpStatusCode(308, "Resume Incomplete"), headersOf("Range", "bytes=0-1048575"))
                    } else if (contentRange.startsWith("bytes 1048576-")) {
                        // Успешное завершение передачи
                        respond("""
                            {
                                "id": "resumed_file_id",
                                "name": "large.bin",
                                "mimeType": "application/octet-stream",
                                "size": "5242880",
                                "modifiedTime": "2026-10-23T12:00:00.000Z",
                                "md5Checksum": "resumed_hash"
                            }
                        """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("Error", HttpStatusCode.BadRequest)
                    }
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val uploader = DriveUploader(driveClient, resolver, resumableThresholdBytes = 1024, chunkSize = 1048576)

        val totalSize = 5242880L
        val data = ByteArray(totalSize.toInt()) { (it % 128).toByte() }
        val buffer = Buffer().also { it.write(data) }

        val entry = uploader.upload(RemotePath("large.bin"), buffer)
        assertEquals("resumed_hash", entry.revision)
        assertEquals(totalSize, entry.size)
    }
}
```

- [ ] **Step 2: Запустить тест и проверить падение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:jvmTest --tests dev.mimir.drive.upload.DriveUploaderTest`  
Ожидается: Ошибка компиляции (`DriveUploader` отсутствует).

- [ ] **Step 3: Реализовать `DriveUploader.kt` и интегрировать в `GoogleDriveStorage.write`**

1. Реализовать определение существующего файла: если файл уже существует — обновление (`PATCH`), если нет — создание нового (`POST`).
2. При размере содержимого < `resumableThresholdBytes` выполнить multipart/related simple upload.
3. При размере ≥ `resumableThresholdBytes` инициализировать resumable-сессию, передавать чанками, при возникновении сбоя делать запрос текущего диапазона и возобновлять загрузку со следующего байта.
4. Вызвать `DriveUploader.upload` из `GoogleDriveStorage.write(path, data)`.

- [ ] **Step 4: Запустить тесты и проверить успешное прохождение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:allTests`  
Ожидается: BUILD SUCCESSFUL, тесты пройдены.

- [ ] **Step 5: Коммит изменений**

Команда: `rtk git add drive/ && rtk oco`

---

### Task 8: Реализация Changes Feed в `GoogleDriveStorage` и регистрация `GoogleDriveStorageProvider`

**Files:**
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt`
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorageProvider.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveChangeFeedTest.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageProviderTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class GoogleDriveStorage(...) : ChangeFeedStorage
  class GoogleDriveStorageProvider : RemoteStorageProvider
  ```

- [ ] **Step 1: Написать падающий тест для `ChangeFeedStorage` в `GoogleDriveStorage`**

В `drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveChangeFeedTest.kt`:
```kotlin
package dev.mimir.drive

import dev.mimir.core.ChangeFeedStorage
import dev.mimir.core.RemotePath
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleDriveChangeFeedTest {
    @Test
    fun testChangeFeedFetchChangesAndMapToRemotePath() = runTest {
        val mockEngine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.parameters["q"]?.contains("'root' in parents") == true -> {
                    respond("""{"files":[{"id":"mimir_root","name":"Mimir","mimeType":"application/vnd.google-apps.folder"}]}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/changes/startPageToken" -> {
                    respond("""{"startPageToken":"start_token_1"}""", HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath == "/drive/v3/changes" && request.url.parameters["pageToken"] == "start_token_1" -> {
                    respond("""
                        {
                            "newStartPageToken": "start_token_2",
                            "changes": [
                                {
                                    "fileId": "file_ch1",
                                    "removed": false,
                                    "file": {
                                        "id": "file_ch1",
                                        "name": "updated.md",
                                        "mimeType": "text/markdown",
                                        "size": "42",
                                        "modifiedTime": "2026-10-23T14:00:00.000Z",
                                        "md5Checksum": "chk123",
                                        "parents": ["mimir_root"],
                                        "trashed": false
                                    }
                                },
                                {
                                    "fileId": "file_ch2",
                                    "removed": true
                                }
                            ]
                        }
                    """.trimIndent(), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond("""{"files":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val authClient = GoogleAuthClient(http, "client-id")
        val storage = InMemoryTokenStorage(OAuthTokens("access", "refresh", 2000000000L))
        val driveClient = DriveHttpClient(http, storage, authClient)
        val resolver = DrivePathResolver(driveClient, "Mimir")
        val driveStorage = GoogleDriveStorage(driveClient, resolver)

        val feedStorage = driveStorage as ChangeFeedStorage
        val token = feedStorage.getLatestChangeToken()
        assertEquals("start_token_1", token)

        val changes = feedStorage.getChanges(token)
        assertEquals("start_token_2", changes.nextToken)
        assertEquals(2, changes.changes.size)
        assertEquals(RemotePath("updated.md"), changes.changes[0].path)
        assertEquals(false, changes.changes[0].isDeleted)
        assertTrue(changes.changes[1].isDeleted)
    }
}
```

- [ ] **Step 2: Запустить тест и проверить падение**

Команда: `rtk mise x java@temurin-27 -- ./gradlew :drive:jvmTest --tests dev.mimir.drive.GoogleDriveChangeFeedTest`  
Ожидается: FAIL из-за неприведения `GoogleDriveStorage` к `ChangeFeedStorage`.

- [ ] **Step 3: Реализовать `ChangeFeedStorage` в `GoogleDriveStorage.kt` и обновить `GoogleDriveStorageProvider.kt`**

1. Добавить `ChangeFeedStorage` в список интерфейсов `GoogleDriveStorage`.
2. Реализовать `getLatestChangeToken()` через запрос `GET /drive/v3/changes/startPageToken`.
3. Реализовать `getChanges(sinceToken: String)` через запрос `GET /drive/v3/changes` с фильтрацией файлов, принадлежащих корню Mimir, и маппингом на `RemotePath`.
4. В `GoogleDriveStorageProvider` установить `capabilities = StorageCapabilities(changeFeed = true)` и обновить метод `create(config)`.

- [ ] **Step 4: Запустить полный набор тестов проекта**

Команда: `rtk mise x java@temurin-27 -- ./gradlew check`  
Ожидается: BUILD SUCCESSFUL на всех таргетах.

- [ ] **Step 5: Оформить changeset и закоммитить изменения**

Команды:
1. `rtk pnpm changeset` (выбрать minor для core и drive: «Implement Google Drive storage adapter and OAuth flows»).
2. `rtk git add . && rtk oco`
