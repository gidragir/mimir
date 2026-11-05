package dev.mimir.runtime.login

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.auth.TokenStorage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class BrowserLoginProviderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val browser = HttpClient(CIO)
    private val google = mutableListOf<HttpClient>()
    private var provider: BrowserLoginProvider? = null
    private var tokenFails = false
    private var tokenError: Throwable? = null

    @AfterTest
    fun cleanup() {
        provider?.close()
        scope.cancel()
        browser.close()
        google.forEach { it.close() }
    }

    private fun run(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }

    private fun googleClient(engine: MockEngine): GoogleAuthClient {
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        google += http
        return GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Desktop, "desktop-id", null))
    }

    private fun defaultGoogle() = googleClient(
        MockEngine { request ->
            if (tokenFails) throw IOException("network down")
            tokenError?.let { throw it }
            val body = when {
                request.url.encodedPath.endsWith("/token") ->
                    """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""
                request.url.encodedPath.endsWith("/userinfo") -> """{"email":"u@x","name":"U"}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private fun provider(
        timeout: Duration = 5.minutes,
        storage: TokenStorage = InMemoryTokenStorage(),
        client: GoogleAuthClient = defaultGoogle(),
    ) = BrowserLoginProvider(client, storage, scope, Dispatchers.IO, timeout).also { provider = it }

    private suspend fun BrowserLoginProvider.startUrl(): String =
        assertIs<DaemonLoginStart.OpenUrl>(start()).url

    private fun port(authUrl: String) = Regex("redirect_uri=http://127\\.0\\.0\\.1:(\\d+)/callback").find(authUrl)!!.groupValues[1].toInt()
    private fun state(authUrl: String) = Regex("state=([0-9a-f]+)").find(authUrl)!!.groupValues[1]

    private fun isOpen(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: IOException) {
        false
    }

    private suspend fun waitClosed(port: Int) = withTimeout(15_000) { while (isOpen(port)) delay(50) }

    private suspend fun awaitNotPending(provider: LoginProvider): DaemonLoginPoll = withTimeout(15_000) {
        var poll = provider.poll()
        while (poll == DaemonLoginPoll.Pending) {
            delay(50)
            poll = provider.poll()
        }
        poll
    }

    @Test
    fun successfulCallbackStoresDesktopTokens() = run {
        val storage = InMemoryTokenStorage()
        val provider = provider(storage = storage)
        assertEquals(DaemonLoginMethod.Browser, provider.method)
        val url = provider.startUrl()

        val page = browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}").bodyAsText()

        assertTrue("Готово" in page)
        assertEquals(DaemonLoginPoll.Authorized("u@x"), awaitNotPending(provider))
        assertEquals(GoogleClientKind.Desktop, storage.load()!!.clientKind)
        assertTrue(provider.tokenSource.hasCredentials())
        waitClosed(port(url))
    }

    @Test
    fun deniedCallbackFailsWithHumanReason() = run {
        val storage = InMemoryTokenStorage()
        val provider = provider(storage = storage)
        val url = provider.startUrl()

        val page = browser.get("http://127.0.0.1:${port(url)}/callback?error=access_denied&state=${state(url)}").bodyAsText()

        assertTrue("Вход отменён" in page)
        assertEquals(DaemonLoginPoll.Failed("Вход отменён"), awaitNotPending(provider))
        assertNull(storage.load())
        waitClosed(port(url))
    }

    @Test
    fun wrongStateIsRejected() = run {
        val storage = InMemoryTokenStorage()
        val provider = provider(storage = storage)
        val url = provider.startUrl()
        val base = "http://127.0.0.1:${port(url)}/callback"

        assertEquals(HttpStatusCode.BadRequest, browser.get("$base?code=c&state=bad").status)
        delay(300)
        assertEquals(DaemonLoginPoll.Pending, provider.poll())
        assertNull(storage.load())

        browser.get("$base?code=c&state=${state(url)}")
        assertEquals(DaemonLoginPoll.Authorized("u@x"), awaitNotPending(provider))
    }

    @Test
    fun timeoutFails() = run {
        val provider = provider(timeout = 200.milliseconds)
        val url = provider.startUrl()

        assertEquals(DaemonLoginPoll.Failed("Время ожидания истекло"), awaitNotPending(provider))
        waitClosed(port(url))
    }

    @Test
    fun restartCancelsPreviousAttempt() = run {
        val provider = provider()
        val first = provider.startUrl()
        val second = provider.startUrl()

        waitClosed(port(first))
        assertTrue(isOpen(port(second)))
        assertEquals(DaemonLoginPoll.Pending, provider.poll())

        // Старый state не принимается и новым приёмником.
        assertEquals(HttpStatusCode.BadRequest, browser.get("http://127.0.0.1:${port(second)}/callback?code=c&state=${state(first)}").status)
        assertEquals(DaemonLoginPoll.Pending, provider.poll())

        browser.get("http://127.0.0.1:${port(second)}/callback?code=c&state=${state(second)}")
        assertEquals(DaemonLoginPoll.Authorized("u@x"), awaitNotPending(provider))
    }

    @Test
    fun restartDiscardsStaleAttemptPastTokenExchange() = run {
        val firstUserinfoEntered = CompletableDeferred<Unit>()
        var tokenCalls = 0
        val client = googleClient(
            MockEngine { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/token") -> {
                        val n = synchronized(this@BrowserLoginProviderTest) { ++tokenCalls }
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
        )
        val storage = InMemoryTokenStorage()
        val provider = provider(storage = storage, client = client)

        val first = provider.startUrl()
        browser.get("http://127.0.0.1:${port(first)}/callback?code=c1&state=${state(first)}")
        withTimeout(15_000) { firstUserinfoEntered.await() }

        val second = provider.startUrl()
        delay(300)
        // Отменённая попытка не должна ничего сохранить.
        assertNull(storage.load())
        assertEquals(DaemonLoginPoll.Pending, provider.poll())

        browser.get("http://127.0.0.1:${port(second)}/callback?code=c2&state=${state(second)}")
        assertEquals(DaemonLoginPoll.Authorized("second@x"), awaitNotPending(provider))
        assertEquals("at2", storage.load()!!.accessToken)
    }

    @Test
    fun tokenExchangeNetworkErrorFails() = run {
        val storage = InMemoryTokenStorage()
        val provider = provider(storage = storage)
        tokenFails = true
        val url = provider.startUrl()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(DaemonLoginPoll.Failed("Не удалось связаться с Google"), awaitNotPending(provider))
        assertNull(storage.load())
    }

    @Test
    fun unresolvedHostFailsAsNetworkError() = run {
        val provider = provider()
        tokenError = java.nio.channels.UnresolvedAddressException()
        val url = provider.startUrl()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(DaemonLoginPoll.Failed("Не удалось связаться с Google"), awaitNotPending(provider))
    }

    @Test
    fun googleRejectionFailsAsRejected() = run {
        val provider = provider()
        tokenError = dev.mimir.drive.client.DriveAuthException(HttpStatusCode.BadRequest, "invalid_request")
        val url = provider.startUrl()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(DaemonLoginPoll.Failed("Google не принял вход"), awaitNotPending(provider))
    }

    @Test
    fun localSaveFailureIsNotReportedAsNetworkError() = run {
        val failing = object : TokenStorage {
            override suspend fun load(): OAuthTokens? = null
            override suspend fun save(tokens: OAuthTokens) = throw IOException("disk full")
            override suspend fun clear() = Unit
        }
        val provider = provider(storage = failing)
        val url = provider.startUrl()

        browser.get("http://127.0.0.1:${port(url)}/callback?code=c&state=${state(url)}")

        assertEquals(DaemonLoginPoll.Failed("Не удалось сохранить вход"), awaitNotPending(provider))
    }

    @Test
    fun signOutClearsStoredTokens() = run {
        val storage = InMemoryTokenStorage(OAuthTokens("a", "r", 4_000_000_000L, clientKind = GoogleClientKind.Desktop))
        val provider = provider(storage = storage)

        provider.signOut()

        assertNull(storage.load())
    }

    @Test
    fun cancelStopsAttemptWithoutSaving() = run {
        val storage = InMemoryTokenStorage()
        val provider = provider(storage = storage)
        val url = provider.startUrl()

        provider.cancel()

        waitClosed(port(url))
        assertIs<DaemonLoginPoll.Failed>(provider.poll())
        assertNull(storage.load())
    }

    @Test
    fun pollBeforeStartFails() = run {
        assertIs<DaemonLoginPoll.Failed>(provider().poll())
    }

    @Test
    fun receiverStopsOnceFromConcurrentCloseAndAwait() = run {
        val receiver = BrowserLoginReceiver(Dispatchers.IO, timeout = 100.milliseconds)
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
    fun testSuccessPageContainsRedirectScriptAndManualButton() = run {
        val receiver = BrowserLoginReceiver(Dispatchers.IO, redirectScheme = "mimir")
        receiver.start()
        receiver.expectedState = "state123"
        val body = browser.get("http://127.0.0.1:${receiver.port}/callback?code=test_code&state=state123").bodyAsText()
        assertTrue(body.contains("""window.location.replace("mimir://authorized");"""))
        assertTrue(body.contains("""href="mimir://authorized""""))
        assertFalse(body.contains("test_code"))
        receiver.close()
    }

    @Test
    fun testSuccessPageWithoutSchemeDoesNotContainRedirect() = run {
        val receiver = BrowserLoginReceiver(Dispatchers.IO, redirectScheme = null)
        receiver.start()
        receiver.expectedState = "state456"
        val body = browser.get("http://127.0.0.1:${receiver.port}/callback?code=test_code&state=state456").bodyAsText()
        assertFalse(body.contains("window.location.replace"))
        assertFalse(body.contains("mimir://"))
        receiver.close()
    }
}
