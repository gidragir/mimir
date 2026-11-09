package dev.mimir.obsidian.net

import dev.mimir.core.RemoteUnavailableException
import dev.mimir.drive.auth.GoogleAuthClient
import dev.mimir.drive.auth.GoogleAuthClients
import dev.mimir.drive.auth.GoogleClientCredentials
import dev.mimir.drive.auth.GoogleClientKind
import dev.mimir.drive.auth.InMemoryTokenStorage
import dev.mimir.drive.auth.OAuthTokens
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.obsidian.fakes.FakeRequestUrl
import dev.mimir.obsidian.platform.UrlResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObsidianHttpEngineTest {
    @Test
    fun forwardsMethodHeadersAndBody() = runTest {
        val fake = FakeRequestUrl()
        obsidianHttpClient(fake).post("https://example.com/x") {
            header(HttpHeaders.Authorization, "Bearer t")
            contentType(ContentType.Application.Json)
            setBody("""{"a":1}""")
        }
        val r = fake.lastRequest
        assertEquals("POST", r.method)
        assertEquals("https://example.com/x", r.url)
        assertEquals("Bearer t", r.headers.entries.first { it.key.equals("Authorization", true) }.value)
        assertEquals("""{"a":1}""", r.body!!.decodeToString())
    }

    @Test
    fun mapsStatusHeadersAndBody() = runTest {
        val fake = FakeRequestUrl { UrlResponse(206, mapOf("content-range" to "bytes 0-2/10"), byteArrayOf(1, 2, 3)) }
        val response = obsidianHttpClient(fake).get("https://example.com/f")
        assertEquals(206, response.status.value)
        assertEquals("bytes 0-2/10", response.headers["Content-Range"])
        assertContentEquals(byteArrayOf(1, 2, 3), response.bodyAsBytes())
    }

    @Test
    fun errorStatusIsNotThrownByEngine() = runTest {
        val fake = FakeRequestUrl { UrlResponse(404, emptyMap(), ByteArray(0)) }
        assertEquals(404, obsidianHttpClient(fake).get("https://example.com/f").status.value)
    }

    @Test
    fun rejectionWithoutStatusBecomesNetworkUnavailable() = runTest {
        val fake = FakeRequestUrl { throw Throwable("net::ERR_INTERNET_DISCONNECTED") }
        assertFailsWith<NetworkUnavailableException> { obsidianHttpClient(fake).get("https://example.com/f") }
    }

    @Test
    fun cancellationPropagates() = runTest {
        val fake = FakeRequestUrl { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { obsidianHttpClient(fake).get("https://example.com/f") }
    }

    @Test
    fun networkUnavailableMapsToRemoteUnavailable() = runTest {
        val fake = FakeRequestUrl { throw Throwable("net::ERR") }
        val http = obsidianHttpClient(fake)
        val auth = GoogleAuthClient(http, GoogleClientCredentials(GoogleClientKind.Device, "id", null))
        val drive = DriveHttpClient(http, InMemoryTokenStorage(OAuthTokens("a", "r", 2000000000L)), GoogleAuthClients(null, auth))
        assertFailsWith<RemoteUnavailableException> {
            drive.executeWithRetry { token -> drive.httpClient.get("https://www.googleapis.com/drive/v3/about") { header(HttpHeaders.Authorization, "Bearer $token") } }
        }
    }
}
