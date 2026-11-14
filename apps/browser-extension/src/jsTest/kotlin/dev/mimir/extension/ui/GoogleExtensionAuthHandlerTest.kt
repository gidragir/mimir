package dev.mimir.extension.ui

import dev.mimir.core.testing.TestClock
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.extension.platform.ExtensionTokenStorage
import dev.mimir.extension.platform.FakeExtensionPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class GoogleExtensionAuthHandlerTest {
    private val tokenRequests = mutableListOf<String>()

    private fun handler(platform: FakeExtensionPlatform): GoogleExtensionAuthHandler {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.host == "oauth2.googleapis.com") {
                    tokenRequests += request.body.toByteArray().decodeToString()
                    respond("""{"access_token":"acc","refresh_token":"ref","expires_in":3600}""", HttpStatusCode.OK, json)
                } else {
                    respond("""{"email":"me@example.com","name":"Me"}""", HttpStatusCode.OK, json)
                }
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val desktop = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Desktop, "cid", "secret"))
        return GoogleExtensionAuthHandler(platform, GoogleAuthClients(desktop, null), ExtensionTokenStorage(platform))
    }

    @Test
    fun desktopClientSignsInThroughLoopbackRedirectWithoutRegisteringAnything() = runTest {
        val platform = FakeExtensionPlatform()
        platform.loopbackResponder = { authUrl ->
            val query = Url(authUrl).parameters
            "${query["redirect_uri"]}?code=the-code&state=${query["state"]}"
        }
        val account = handler(platform).signInWithWebAuthFlow()

        assertEquals("me@example.com", account.email)
        val authUrl = Url(platform.loopbackAuthUrls.single())
        assertContains(authUrl.parameters["redirect_uri"].orEmpty(), "http://127.0.0.1:")
        assertContains(tokenRequests.single(), "code=the-code")
        assertContains(tokenRequests.single(), "redirect_uri=http")
        assertNotNull(platform.getLocalString("auth.refreshToken"))
        assertNotNull(platform.getLocalString("auth.account"))
    }

    @Test
    fun stateMismatchAbortsSignInWithoutExchangingTheCode() = runTest {
        val platform = FakeExtensionPlatform()
        platform.loopbackResponder = { authUrl -> "${Url(authUrl).parameters["redirect_uri"]}?code=evil&state=forged" }

        assertFailsWith<IllegalStateException> { handler(platform).signInWithWebAuthFlow() }
        assertEquals(0, tokenRequests.size)
        assertNull(platform.getLocalString("auth.refreshToken"))
    }

    @Test
    fun startDeviceCodeAdds5SecondsDelayAfterSlowDown() = runTest {
        val platform = FakeExtensionPlatform()
        val clock = TestClock()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        var polls = 0
        val http = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    "/device/code" in url -> respond(
                        """{"device_code":"dev-1","user_code":"ABCD-EFGH","verification_url":"https://google.com/device","expires_in":600,"interval":5}""",
                        HttpStatusCode.OK,
                        json,
                    )
                    "/token" in url -> {
                        polls++
                        when (polls) {
                            1 -> respond("""{"error":"slow_down"}""", HttpStatusCode.BadRequest, json)
                            else -> respond("""{"access_token":"acc","refresh_token":"ref","expires_in":3600}""", HttpStatusCode.OK, json)
                        }
                    }
                    else -> respond("""{"email":"device@example.com","name":"Device"}""", HttpStatusCode.OK, json)
                }
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val deviceClient = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "cid", null), clock = clock)
        val auth = GoogleExtensionAuthHandler(platform, GoogleAuthClients(null, deviceClient), ExtensionTokenStorage(platform), clock = clock)

        val account = auth.startDeviceCode {}
        assertEquals("device@example.com", account.email)
        assertEquals(2, polls)
        // First poll after 5s -> slow_down -> second poll after 5s + 5s = 15s total
        assertEquals(15.seconds.inWholeMilliseconds, currentTime)
    }
}
