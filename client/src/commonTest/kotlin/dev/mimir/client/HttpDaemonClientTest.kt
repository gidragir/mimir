package dev.mimir.client

import dev.mimir.core.SyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.pluginOrNull
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
import kotlin.test.assertIs
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
        assertEquals(SyncStatus.Offline, client.status.value, "UI must not keep showing the last Idle/Syncing")
    }

    @Test
    fun daemonClientConfigInstallsTimeouts() {
        val http = HttpClient(MockEngine { respond("{}") }) { installDaemonTimeouts() }
        assertTrue(http.pluginOrNull(io.ktor.client.plugins.HttpTimeout) != null)
        http.close()
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
        val start = assertIs<DaemonLoginStart.ShowDeviceCode>(client.startLogin(DaemonLoginMethod.DeviceCode)).code
        assertEquals("d", start.deviceCode)
        assertEquals("U-1", start.userCode)

        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.OK to """{"status":"pending"}"""
        assertEquals(DaemonLoginPoll.Pending, client.pollLogin())

        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.OK to """{"status":"authorized","email":"me@x"}"""
        assertEquals(DaemonLoginPoll.Authorized("me@x"), client.pollLogin())

        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.BadRequest to """{"status":"expired","error":"Code has expired"}"""
        assertEquals(DaemonLoginPoll.Failed("Code has expired"), client.pollLogin())
    }

    @Test
    fun devicePollSendsTheStartedDeviceCode() = runTest {
        val polledCodes = mutableListOf<String?>()
        val mockEngine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v1/auth/device/start" ->
                    """{"device_code":"dev-42","user_code":"U","verification_url":"https://g/device","expires_in":1800,"interval":5}"""
                "/api/v1/auth/device/poll" -> {
                    polledCodes += request.url.parameters["device_code"]
                    """{"status":"pending"}"""
                }
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpDaemonClient(baseUrl = "http://127.0.0.1:1", scope = backgroundScope, httpClient = HttpClient(mockEngine))

        client.startLogin(DaemonLoginMethod.DeviceCode)
        client.pollLogin()

        assertEquals(listOf<String?>("dev-42"), polledCodes)
    }

    @Test
    fun pollBeforeStartFailsAndSystemAccountIsUnsupported() = runTest {
        val client = HttpDaemonClient(
            baseUrl = "http://127.0.0.1:1",
            scope = backgroundScope,
            httpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound, jsonHeaders) }),
        )

        assertIs<DaemonLoginPoll.Failed>(client.pollLogin())
        assertFailsWith<UnsupportedOperationException> { client.startLogin(DaemonLoginMethod.SystemAccount) }
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
        assertEquals(DaemonLoginStart.OpenUrl("https://accounts.google.com/auth"), client.startLogin(DaemonLoginMethod.Browser))

        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"pending"}"""
        assertEquals(DaemonLoginPoll.Pending, client.pollLogin())

        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"authorized","email":"me@x"}"""
        assertEquals(DaemonLoginPoll.Authorized("me@x"), client.pollLogin())

        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"failed","error":"Вход отменён"}"""
        assertEquals(DaemonLoginPoll.Failed("Вход отменён"), client.pollLogin())
    }

    @Test
    fun updateCommandsUseTheUpdateRoutesAndMapErrors() = runTest {
        val requests = mutableListOf<String>()
        val replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()
        val mockEngine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            requests += key
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, jsonHeaders)
        }
        val client = HttpDaemonClient("http://127.0.0.1:1", scope = backgroundScope, httpClient = HttpClient(mockEngine))

        replies["GET /api/v1/update"] = HttpStatusCode.OK to
            """{"currentVersion":"0.1.0","status":"AVAILABLE","latestVersion":"0.2.0","releaseNotes":"Fixes","capability":"DOWNLOAD_AND_LAUNCH","htmlUrl":"https://x/r","extra":1}"""
        val available = client.getUpdate()
        assertEquals(UpdateStatus.AVAILABLE, available.status)
        assertEquals("0.2.0", available.latestVersion)
        assertEquals("DOWNLOAD_AND_LAUNCH", available.capability)

        replies["POST /api/v1/update/check"] = HttpStatusCode.OK to """{"currentVersion":"0.1.0","status":"IDLE"}"""
        assertEquals(UpdateStatus.IDLE, client.checkUpdates().status)

        replies["POST /api/v1/update/check"] = HttpStatusCode.BadGateway to """{"error":"rate limit"}"""
        assertEquals("rate limit", assertFailsWith<IllegalStateException> { client.checkUpdates() }.message)

        replies["POST /api/v1/update/download"] = HttpStatusCode.Accepted to """{"status":"started"}"""
        client.downloadUpdate()
        replies["POST /api/v1/update/download"] = HttpStatusCode.Conflict to """{"error":"A download is already in progress"}"""
        assertFailsWith<IllegalStateException> { client.downloadUpdate() }

        replies["POST /api/v1/update/apply"] = HttpStatusCode.OK to
            """{"result":"notification","message":"Update details: https://x/r","requiresRestart":false,"url":"https://x/r"}"""
        assertEquals("https://x/r", client.applyUpdate().url)
        replies["POST /api/v1/update/apply"] = HttpStatusCode.Conflict to """{"error":"A sync run is in progress; try again when it finishes"}"""
        assertTrue(assertFailsWith<IllegalStateException> { client.applyUpdate() }.message!!.contains("sync run"))
    }
}
