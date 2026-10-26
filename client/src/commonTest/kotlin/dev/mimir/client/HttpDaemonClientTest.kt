package dev.mimir.client

import dev.mimir.core.SyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HttpDaemonClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

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
                    headers = jsonHeaders,
                )
            }
        }
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:47821",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
            pollInterval = 100.milliseconds,
        )
        withTimeout(5_000) {
            while (client.connectionStatus.value != DaemonConnectionStatus.Connected) {
                delay(50)
            }
        }
        assertEquals(DaemonConnectionStatus.Connected, client.connectionStatus.value)

        shouldFail = true
        withTimeout(5_000) {
            while (client.connectionStatus.value != DaemonConnectionStatus.Reconnecting) {
                delay(50)
            }
        }
        assertEquals(DaemonConnectionStatus.Reconnecting, client.connectionStatus.value)
    }

    @Test
    fun testPollsStatusAndConflicts() = runTest {
        val requests = mutableListOf<String>()
        val tokens = mutableListOf<String?>()
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        replies["GET /api/v1/status"] = HttpStatusCode.OK to
            """{"status":"idle","label":"Mimir: idle","vaultPath":"/v","lastSyncAt":"2026-10-24T10:00:00Z","isLocked":false,"activeConflicts":1,"accountEmail":"me@x"}"""
        replies["GET /api/v1/conflicts"] = HttpStatusCode.OK to
            """{"conflicts":[{"originalPath":"A.md","conflictPath":"A (conflicted copy 2026-10-24-101010).md","createdAt":"2026-10-24T10:10:10Z"}]}"""

        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            requests += key
            tokens += request.headers[HttpHeaders.Authorization]
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }

        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            token = "tok",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
            pollInterval = 20.milliseconds,
        )

        withTimeout(5_000) {
            while (client.conflictsFlow.value.isEmpty()) {
                delay(20)
            }
        }
        assertEquals(SyncStatus.Idle, client.status.value)
        assertEquals("/v", client.vaultPath)
        assertFalse(client.isLocked)
        assertEquals("me@x", client.accountEmail)
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), client.lastSyncTime)
        assertEquals(listOf("A.md"), client.conflictsFlow.value.map { it.originalPath })
        assertTrue(tokens.all { it == "Bearer tok" })
        assertEquals(DaemonConnectionStatus.Connected, client.connectionStatus.value)
    }

    @Test
    fun testParsesProgressFromStatus() = runTest {
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        replies["GET /api/v1/status"] = HttpStatusCode.OK to
            """{"status":"syncing","label":"Mimir: syncing","isLocked":false,"activeConflicts":0,"progress":{"phase":"applying","startedAt":"2026-10-24T10:00:00Z","total":87,"done":12}}"""

        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }

        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
            pollInterval = 20.milliseconds,
        )

        withTimeout(5_000) {
            while (client.progress.value == null) {
                delay(20)
            }
        }
        assertEquals(12, client.progress.value?.done)

        replies["GET /api/v1/status"] = HttpStatusCode.OK to """{"status":"idle","label":"Mimir: idle","isLocked":false,"activeConflicts":0}"""
        withTimeout(5_000) {
            while (client.progress.value != null) {
                delay(20)
            }
        }
        assertEquals(null, client.progress.value)
    }

    @Test
    fun testTriggerSyncMapsStatusCodes() = runTest {
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
        )

        replies["POST /api/v1/sync"] = HttpStatusCode.Accepted to """{"status":"started"}"""
        assertTrue(client.triggerSync())

        replies["POST /api/v1/sync"] = HttpStatusCode.Conflict to """{"status":"already_running"}"""
        assertFalse(client.triggerSync())

        replies["POST /api/v1/sync"] = HttpStatusCode.PreconditionFailed to """{"error":"not configured"}"""
        assertFailsWith<SyncNotConfiguredException> { client.triggerSync() }
    }

    @Test
    fun testUnlockMapsErrors() = runTest {
        val bodies = mutableListOf<String>()
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            if (request.method == HttpMethod.Post) bodies += request.body.toByteArray().decodeToString()
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
        )

        replies["POST /api/v1/unlock"] = HttpStatusCode.Forbidden to """{"error":"Invalid encryption password"}"""
        val error = assertFailsWith<InvalidPasswordException> { client.unlock("bad") }
        assertEquals("Invalid encryption password", error.message)

        replies["POST /api/v1/unlock"] = HttpStatusCode.OK to """{"status":"unlocked"}"""
        client.unlock("good")
        assertTrue(bodies.last().contains("\"good\""))
    }

    @Test
    fun testConfigureVaultAndInspect() = runTest {
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
        )

        replies["POST /api/v1/vault"] = HttpStatusCode.BadRequest to """{"error":"Not a directory: /x"}"""
        assertFailsWith<IllegalArgumentException> { client.configureVault("/x") }

        replies["POST /api/v1/vault"] = HttpStatusCode.OK to """{"vaultPath":"/ok","fileCount":3}"""
        assertEquals(3, client.configureVault("/ok"))

        replies["GET /api/v1/vault/inspect"] = HttpStatusCode.OK to
            """{"localFileCount":10,"remoteFileCount":20,"requiresStrategyConfirmation":true}"""
        val inspection = client.inspectVault()
        assertEquals(10, inspection.localFileCount)
        assertEquals(20, inspection.remoteFileCount)
        assertTrue(inspection.requiresStrategyConfirmation)
    }

    @Test
    fun testDeviceLogin() = runTest {
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
        )

        replies["POST /api/v1/auth/device/start"] = HttpStatusCode.OK to
            """{"device_code":"d","user_code":"U-1","verification_url":"https://g/device","expires_in":1800,"interval":5}"""
        val start = client.startDeviceLogin()
        assertEquals("d", start.deviceCode)
        assertEquals("U-1", start.userCode)

        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.OK to """{"status":"pending"}"""
        assertEquals(DaemonDevicePoll.Pending, client.pollDeviceLogin("d"))

        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.OK to """{"status":"authorized","email":"me@x"}"""
        assertEquals(DaemonDevicePoll.Authorized("me@x"), client.pollDeviceLogin("d"))

        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.BadRequest to """{"status":"expired","error":"Code has expired"}"""
        assertEquals(DaemonDevicePoll.Expired("Code has expired"), client.pollDeviceLogin("d"))
    }

    @Test
    fun testBrowserLoginAndMethods() = runTest {
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            scope = backgroundScope,
            httpClient = HttpClient(mockEngine),
        )

        replies["GET /api/v1/auth/methods"] = HttpStatusCode.OK to """{"methods":["browser","device_code","future"]}"""
        assertEquals(setOf(DaemonLoginMethod.Browser, DaemonLoginMethod.DeviceCode), client.loginMethods())

        replies["POST /api/v1/auth/browser/start"] = HttpStatusCode.OK to """{"authUrl":"https://accounts.google.com/auth"}"""
        assertEquals("https://accounts.google.com/auth", client.startBrowserLogin())

        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"pending"}"""
        assertEquals(DaemonBrowserLoginPoll.Pending, client.pollBrowserLogin())

        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"authorized","email":"me@x"}"""
        assertEquals(DaemonBrowserLoginPoll.Authorized("me@x"), client.pollBrowserLogin())

        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"failed","error":"Вход отменён"}"""
        assertEquals(DaemonBrowserLoginPoll.Failed("Вход отменён"), client.pollBrowserLogin())
    }
}
