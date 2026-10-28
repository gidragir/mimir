package dev.mimir.daemon

import dev.mimir.runtime.SyncNotConfiguredException

import dev.mimir.client.DaemonBrowserLoginPoll as BrowserLoginPoll
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertFalse
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
    private var tokenError: Throwable? = null
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
                tokenError?.let { throw it }
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
    fun restartDiscardsStaleAttemptPastTokenExchange() = run {
        val firstUserinfoEntered = CompletableDeferred<Unit>()
        var tokenCalls = 0
        val google = HttpClient(
            MockEngine { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/token") -> {
                        val n = synchronized(this@BrowserLoginTest) { ++tokenCalls }
                        """{"access_token":"at$n","refresh_token":"rt$n","expires_in":3600,"token_type":"Bearer"}"""
                    }
                    request.url.encodedPath.endsWith("/userinfo") -> {
                        if (request.headers[HttpHeaders.Authorization] == "Bearer at1") {
                            // Первая попытка уже прошла обмен кода и ждёт профиль, когда вход перезапускают.
                            firstUserinfoEntered.complete(Unit)
                            awaitCancellation()
                        }
                        """{"email":"second@x"}"""
                    }
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val runtime = DaemonRuntime(
            DaemonConfig(googleDesktopClientId = "desktop-id", configDir = configDir.toString()),
            httpClient = google,
        ).also { runtime = it }

        val first = runtime.startBrowserLogin()
        browser.get("http://127.0.0.1:${port(first)}/callback?code=c1&state=${state(first)}")
        withTimeout(15_000) { firstUserinfoEntered.await() }

        val second = runtime.startBrowserLogin()
        delay(300)
        // Отменённая попытка не должна ничего сохранить.
        assertNull(runtime.tokenStorage.load())
        assertTrue(!Files.exists(configDir.resolve("account.json")))
        assertEquals(BrowserLoginPoll.Pending, runtime.pollBrowserLogin())

        browser.get("http://127.0.0.1:${port(second)}/callback?code=c2&state=${state(second)}")
        assertEquals(BrowserLoginPoll.Authorized("second@x"), awaitNotPending(runtime))
        assertEquals("at2", runtime.tokenStorage.load()!!.accessToken)
        assertEquals("second@x", runtime.accountEmail)
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
    fun unresolvedHostFailsAsNetworkError() = run {
        val runtime = runtime()
        tokenError = java.nio.channels.UnresolvedAddressException()
        val url = runtime.startBrowserLogin()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(BrowserLoginPoll.Failed("Не удалось связаться с Google"), awaitNotPending(runtime))
    }

    @Test
    fun googleRejectionFailsAsRejected() = run {
        val runtime = runtime()
        tokenError = dev.mimir.drive.client.DriveAuthException(HttpStatusCode.BadRequest, "invalid_request")
        val url = runtime.startBrowserLogin()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(BrowserLoginPoll.Failed("Google не принял вход"), awaitNotPending(runtime))
    }

    @Test
    fun localSaveFailureIsNotReportedAsNetworkError() = run {
        val runtime = runtime()
        // account.json — непустой каталог: атомарная запись аккаунта падает с IOException после обмена кода.
        Files.createDirectories(configDir.resolve("account.json").resolve("x"))
        val url = runtime.startBrowserLogin()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(BrowserLoginPoll.Failed("Не удалось сохранить вход"), awaitNotPending(runtime))
    }

    @Test
    fun startWithoutDesktopClientIsNotConfigured() = run {
        val runtime = runtime(desktopId = null)
        assertFailsWith<SyncNotConfiguredException> { runtime.startBrowserLogin() }
    }

    @Test
    fun receiverStopsOnceFromConcurrentCloseAndAwait() = run {
        val receiver = BrowserLoginReceiver(timeout = 100.milliseconds)
        receiver.start()
        val port = receiver.port
        val closers = List(8) { Thread { receiver.close() } }
        closers.forEach { it.start() }
        assertNull(receiver.awaitCallback())
        closers.forEach { it.join() }
        receiver.close()
        waitClosed(port)
    }

    @Test
    fun pollBeforeStartFails() = run {
        assertNotNull(runtime().pollBrowserLogin().let { it as? BrowserLoginPoll.Failed })
    }

    @Test
    fun testSuccessPageContainsRedirectScriptAndManualButton() = run {
        val receiver = BrowserLoginReceiver(redirectScheme = "mimir")
        receiver.start()
        receiver.expectedState = "state123"
        val client = HttpClient(CIO)
        val response = client.get("http://127.0.0.1:${receiver.port}/callback?code=test_code&state=state123")
        val body = response.bodyAsText()
        assertTrue(body.contains("""window.location.replace("mimir://authorized");"""))
        assertTrue(body.contains("""href="mimir://authorized""""))
        assertFalse(body.contains("test_code"))
        receiver.close()
        client.close()
    }

    @Test
    fun testSuccessPageWithoutSchemeDoesNotContainRedirect() = run {
        val receiver = BrowserLoginReceiver(redirectScheme = null)
        receiver.start()
        receiver.expectedState = "state456"
        val client = HttpClient(CIO)
        val response = client.get("http://127.0.0.1:${receiver.port}/callback?code=test_code&state=state456")
        val body = response.bodyAsText()
        assertFalse(body.contains("window.location.replace"))
        assertFalse(body.contains("mimir://"))
        receiver.close()
        client.close()
    }
}
