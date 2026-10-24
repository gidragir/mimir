package dev.mimir.desktop

import dev.mimir.core.SyncStatus
import dev.mimir.daemon.DevicePoll
import dev.mimir.daemon.InvalidPasswordException
import dev.mimir.daemon.SyncNotConfiguredException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class HttpDaemonClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()
    private var bodies = mutableListOf<String>()
    private var tokens = mutableListOf<String?>()
    private var replies = mutableMapOf<String, Pair<HttpStatusCode, String>>()

    private fun client(scope: kotlinx.coroutines.CoroutineScope, token: String? = null) = HttpDaemonClient(
        baseUrl = "http://127.0.0.1:1",
        token = token,
        scope = scope,
        pollInterval = 20.milliseconds,
        httpClient = HttpClient(MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            requests += key
            tokens += request.headers[HttpHeaders.Authorization]
            if (request.method == HttpMethod.Post) bodies += request.body.toByteArray().decodeToString()
            val (status, body) = replies[key] ?: (HttpStatusCode.NotFound to """{"error":"unexpected $key"}""")
            respond(body, status, json)
        }) { install(ContentNegotiation) { json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) } },
    )

    @Test
    fun `polls status and conflicts of the remote daemon`() = runBlocking {
        replies["GET /api/v1/status"] = HttpStatusCode.OK to
            """{"status":"idle","label":"Mimir: idle","vaultPath":"/v","lastSyncAt":"2026-10-24T10:00:00Z","isLocked":false,"activeConflicts":1,"accountEmail":"me@x"}"""
        replies["GET /api/v1/conflicts"] = HttpStatusCode.OK to
            """{"conflicts":[{"originalPath":"A.md","conflictPath":"A (conflicted copy 2026-10-24-101010).md","createdAt":"2026-10-24T10:10:10Z"}]}"""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client(scope, token = "tok")
        withTimeout(5_000) { while (client.conflictsFlow.value.isEmpty()) delay(10) }
        scope.cancel()

        assertEquals(SyncStatus.Idle, client.status.value)
        assertEquals("/v", client.vaultPath)
        assertFalse(client.isLocked)
        assertEquals("me@x", client.accountEmail)
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), client.lastSyncTime)
        assertEquals(listOf("A.md"), client.conflictsFlow.value.map { it.originalPath })
        assertTrue(tokens.all { it == "Bearer tok" })
    }

    @Test
    fun `parses progress from status`() = runBlocking {
        replies["GET /api/v1/status"] = HttpStatusCode.OK to
            """{"status":"syncing","label":"Mimir: syncing","isLocked":false,"progress":{"phase":"applying","startedAt":"2026-10-24T10:00:00Z","total":87,"done":12}}"""
        replies["GET /api/v1/conflicts"] = HttpStatusCode.OK to """{"conflicts":[]}"""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client(scope)
        withTimeout(5_000) { while (client.progress.value == null) delay(10) }
        assertEquals(12, client.progress.value?.done)
        replies["GET /api/v1/status"] = HttpStatusCode.OK to """{"status":"idle","label":"Mimir: idle","isLocked":false}"""
        withTimeout(5_000) { while (client.progress.value != null) delay(10) }
        scope.cancel()
    }

    @Test
    fun `an unreachable daemon keeps the last known state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = client(scope)
        delay(200)
        scope.cancel()
        assertEquals(SyncStatus.NotConfigured, client.status.value)
    }

    @Test
    fun `triggerSync maps 202, 409 and 412`() = runTest {
        val client = client(backgroundScope)
        replies["POST /api/v1/sync"] = HttpStatusCode.Accepted to """{"status":"started"}"""
        assertTrue(client.triggerSync())
        replies["POST /api/v1/sync"] = HttpStatusCode.Conflict to """{"status":"already_running"}"""
        assertFalse(client.triggerSync())
        replies["POST /api/v1/sync"] = HttpStatusCode.PreconditionFailed to """{"error":"not configured"}"""
        assertFailsWith<SyncNotConfiguredException> { client.triggerSync() }
    }

    @Test
    fun `unlock maps a wrong password to InvalidPasswordException`() = runTest {
        val client = client(backgroundScope)
        replies["POST /api/v1/unlock"] = HttpStatusCode.Forbidden to """{"error":"Invalid encryption password"}"""
        val error = assertFailsWith<InvalidPasswordException> { client.unlock("bad") }
        assertEquals("Invalid encryption password", error.message)
        replies["POST /api/v1/unlock"] = HttpStatusCode.OK to """{"status":"unlocked"}"""
        client.unlock("good")
        assertTrue(bodies.last().contains("\"good\""))
    }

    @Test
    fun `configureVault maps 400 to IllegalArgumentException`() = runTest {
        val client = client(backgroundScope)
        replies["POST /api/v1/vault"] = HttpStatusCode.BadRequest to """{"error":"Not a directory: /x"}"""
        assertFailsWith<IllegalArgumentException> { client.configureVault("/x") }
        replies["POST /api/v1/vault"] = HttpStatusCode.OK to """{"status":"configured"}"""
        client.configureVault("/ok")
    }

    @Test
    fun `resolveConflict sends the chosen resolution`() = runTest {
        val client = client(backgroundScope)
        replies["GET /api/v1/conflicts"] = HttpStatusCode.OK to """{"conflicts":[]}"""
        replies["POST /api/v1/conflicts/resolve"] = HttpStatusCode.OK to """{"status":"resolved","path":"A.md"}"""
        client.resolveConflict("A.md", "A (c).md", keepConflict = true)
        assertTrue(bodies.last().contains("keep_conflict"))
        replies["POST /api/v1/conflicts/resolve"] = HttpStatusCode.NotFound to """{"error":"No such conflict"}"""
        assertFailsWith<NoSuchElementException> { client.resolveConflict("A.md", "A (c).md", keepConflict = false) }
    }

    @Test
    fun `device login start and poll`() = runTest {
        val client = client(backgroundScope)
        replies["POST /api/v1/auth/device/start"] = HttpStatusCode.OK to
            """{"device_code":"d","user_code":"U-1","verification_url":"https://g/device","expires_in":1800,"interval":5}"""
        assertEquals("U-1", client.startDeviceLogin().user_code)

        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.OK to """{"status":"pending"}"""
        assertEquals(DevicePoll.Pending, client.pollDeviceLogin("d"))
        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.OK to """{"status":"authorized","email":"me@x"}"""
        assertEquals(DevicePoll.Authorized("me@x"), client.pollDeviceLogin("d"))
        replies["GET /api/v1/auth/device/poll"] = HttpStatusCode.BadRequest to """{"status":"expired","error":"Code has expired"}"""
        assertEquals(DevicePoll.Expired("Code has expired"), client.pollDeviceLogin("d"))
    }

    @Test
    fun `loginMethodsParsesList`() = runTest {
        val client = client(backgroundScope)
        replies["GET /api/v1/auth/methods"] = HttpStatusCode.OK to """{"methods":["browser","device_code","future"]}"""
        assertEquals(setOf(dev.mimir.daemon.LoginMethod.Browser, dev.mimir.daemon.LoginMethod.DeviceCode), client.loginMethods())
    }

    @Test
    fun `startBrowserLoginReturnsUrl`() = runTest {
        val client = client(backgroundScope)
        replies["POST /api/v1/auth/browser/start"] = HttpStatusCode.OK to """{"authUrl":"https://accounts.google.com/o/oauth2/v2/auth?x=1"}"""
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?x=1", client.startBrowserLogin())
        replies["POST /api/v1/auth/browser/start"] = HttpStatusCode.Conflict to """{"error":"Browser sign-in is not configured"}"""
        assertFailsWith<IllegalStateException> { client.startBrowserLogin() }
    }

    @Test
    fun `pollBrowserLoginMapsFailed`() = runTest {
        val client = client(backgroundScope)
        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"pending"}"""
        assertEquals(dev.mimir.daemon.BrowserLoginPoll.Pending, client.pollBrowserLogin())
        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"authorized","email":"me@x"}"""
        assertEquals(dev.mimir.daemon.BrowserLoginPoll.Authorized("me@x"), client.pollBrowserLogin())
        replies["GET /api/v1/auth/browser/poll"] = HttpStatusCode.OK to """{"status":"failed","error":"Вход отменён"}"""
        assertEquals(dev.mimir.daemon.BrowserLoginPoll.Failed("Вход отменён"), client.pollBrowserLogin())
    }
}
