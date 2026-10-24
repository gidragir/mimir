package dev.mimir.daemon

import dev.mimir.drive.auth.GoogleClientKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class BrowserLoginTest {
    private val configDir = Files.createTempDirectory("mimir-browser-cfg")
    private val browser = HttpClient(CIO)
    private var runtime: DaemonRuntime? = null
    private var tokenFails = false
    private val tokenRequests = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        runtime?.close()
        browser.close()
        configDir.toFile().deleteRecursively()
    }

    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }

    private fun runtime(timeout: Duration = 5.minutes, desktopId: String? = "desktop-id"): DaemonRuntime {
        val google = HttpClient(
            MockEngine { request ->
                if (tokenFails) throw IOException("network down")
                val body = when {
                    request.url.encodedPath.endsWith("/token") -> {
                        tokenRequests += request.url.toString()
                        """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""
                    }
                    request.url.encodedPath.endsWith("/userinfo") -> """{"email":"u@x","name":"U"}"""
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val config = DaemonConfig(googleDesktopClientId = desktopId, configDir = configDir.toString())
        return DaemonRuntime(config, httpClient = google, browserLoginTimeout = timeout).also { runtime = it }
    }

    private fun port(authUrl: String) = Regex("redirect_uri=http://127\\.0\\.0\\.1:(\\d+)/callback").find(authUrl)!!.groupValues[1].toInt()
    private fun state(authUrl: String) = Regex("state=([0-9a-f]+)").find(authUrl)!!.groupValues[1]

    private fun isOpen(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: IOException) {
        false
    }

    private suspend fun waitClosed(port: Int) = withTimeout(15_000) { while (isOpen(port)) delay(50) }

    private suspend fun awaitNotPending(runtime: DaemonRuntime): BrowserLoginPoll = withTimeout(15_000) {
        var poll = runtime.pollBrowserLogin()
        while (poll == BrowserLoginPoll.Pending) {
            delay(50)
            poll = runtime.pollBrowserLogin()
        }
        poll
    }

    @Test
    fun successfulCallbackStoresDesktopTokens() = run {
        val runtime = runtime()
        val url = runtime.startBrowserLogin()

        val page = browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}").bodyAsText()

        assertTrue("Готово" in page)
        assertEquals(BrowserLoginPoll.Authorized("u@x"), awaitNotPending(runtime))
        assertEquals(GoogleClientKind.Desktop, runtime.tokenStorage.load()!!.clientKind)
        assertEquals("u@x", runtime.accountEmail)
        waitClosed(port(url))
    }

    @Test
    fun deniedCallbackFailsWithHumanReason() = run {
        val runtime = runtime()
        val url = runtime.startBrowserLogin()

        val page = browser.get("http://127.0.0.1:${port(url)}/callback?error=access_denied&state=${state(url)}").bodyAsText()

        assertTrue("Вход отменён" in page)
        assertEquals(BrowserLoginPoll.Failed("Вход отменён"), awaitNotPending(runtime))
        assertNull(runtime.tokenStorage.load())
        waitClosed(port(url))
    }

    @Test
    fun wrongStateIsRejected() = run {
        val runtime = runtime()
        val url = runtime.startBrowserLogin()
        val base = "http://127.0.0.1:${port(url)}/callback"

        assertEquals(HttpStatusCode.BadRequest, browser.get("$base?code=c&state=bad").status)
        delay(300)
        assertEquals(BrowserLoginPoll.Pending, runtime.pollBrowserLogin())
        assertNull(runtime.tokenStorage.load())

        browser.get("$base?code=c&state=${state(url)}")
        assertEquals(BrowserLoginPoll.Authorized("u@x"), awaitNotPending(runtime))
    }

    @Test
    fun timeoutFails() = run {
        val runtime = runtime(timeout = 200.milliseconds)
        val url = runtime.startBrowserLogin()

        assertEquals(BrowserLoginPoll.Failed("Время ожидания истекло"), awaitNotPending(runtime))
        waitClosed(port(url))
    }

    @Test
    fun restartCancelsPreviousAttempt() = run {
        val runtime = runtime()
        val first = runtime.startBrowserLogin()
        val second = runtime.startBrowserLogin()

        waitClosed(port(first))
        assertTrue(isOpen(port(second)))
        assertEquals(BrowserLoginPoll.Pending, runtime.pollBrowserLogin())

        // Старый state не принимается и новым приёмником.
        assertEquals(HttpStatusCode.BadRequest, browser.get("http://127.0.0.1:${port(second)}/callback?code=c&state=${state(first)}").status)
        assertEquals(BrowserLoginPoll.Pending, runtime.pollBrowserLogin())

        browser.get("http://127.0.0.1:${port(second)}/callback?code=c&state=${state(second)}")
        assertEquals(BrowserLoginPoll.Authorized("u@x"), awaitNotPending(runtime))
    }

    @Test
    fun tokenExchangeNetworkErrorFails() = run {
        val runtime = runtime()
        tokenFails = true
        val url = runtime.startBrowserLogin()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(BrowserLoginPoll.Failed("Не удалось связаться с Google"), awaitNotPending(runtime))
        assertNull(runtime.tokenStorage.load())
    }

    @Test
    fun startWithoutDesktopClientIsNotConfigured() = run {
        val runtime = runtime(desktopId = null)
        assertFailsWith<SyncNotConfiguredException> { runtime.startBrowserLogin() }
    }

    @Test
    fun pollBeforeStartFails() = run {
        assertNotNull(runtime().pollBrowserLogin().let { it as? BrowserLoginPoll.Failed })
    }
}
