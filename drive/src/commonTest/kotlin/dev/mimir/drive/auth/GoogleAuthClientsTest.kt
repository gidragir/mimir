package dev.mimir.drive.auth

import dev.mimir.core.RemoteAuthException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class GoogleAuthClientsTest {
    private var capturedClientId: String? = null

    private fun http(): HttpClient = HttpClient(
        MockEngine { request ->
            capturedClientId = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString()
                ?.split("&")?.firstOrNull { it.startsWith("client_id=") }?.removePrefix("client_id=")
            respond(
                content = """{"access_token":"new_access","expires_in":3600,"token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun client(http: HttpClient, kind: GoogleClientKind, id: String, secret: String? = null) =
        GoogleAuthClient(http, GoogleClientCredentials(kind, id, secret))

    @Test
    fun authorizationUrlNeverContainsClientSecret() {
        val url = client(http(), GoogleClientKind.Desktop, "id", "s3cr3t")
            .buildAuthorizationUrl("http://127.0.0.1:5000/callback", "chal", "st")
        assertFalse("client_secret" in url)
        assertFalse("s3cr3t" in url)
    }

    @Test
    fun tokensAreTaggedWithIssuingClientKind() = runTest {
        val desktop = client(http(), GoogleClientKind.Desktop, "desktop-id", "sec")
        assertEquals(GoogleClientKind.Desktop, desktop.exchangeAuthorizationCode("c", "v", "r").clientKind)
        assertEquals(GoogleClientKind.Desktop, desktop.refreshTokens("r").clientKind)
        val device = client(http(), GoogleClientKind.Device, "device-id")
        assertEquals(GoogleClientKind.Device, device.pollDeviceToken("d").clientKind)
    }

    @Test
    fun legacyTokenJsonWithoutClientKindDecodesAsDevice() {
        val t = Json.decodeFromString<OAuthTokens>("""{"accessToken":"a","refreshToken":"r","expiresAtEpochSeconds":1}""")
        assertEquals(GoogleClientKind.Device, t.clientKind)
    }

    @Test
    fun forKindReturnsMatchingClient() {
        val h = http()
        val d = client(h, GoogleClientKind.Desktop, "desktop-id")
        val v = client(h, GoogleClientKind.Device, "device-id")
        val clients = GoogleAuthClients(d, v)
        assertSame(d, clients.forKind(GoogleClientKind.Desktop))
        assertSame(v, clients.forKind(GoogleClientKind.Device))
        assertNull(GoogleAuthClients(null, v).forKind(GoogleClientKind.Desktop))
    }

    @Test
    fun refreshUsesClientMatchingTokenKind() = runTest {
        val h = http()
        val clients = GoogleAuthClients(
            client(h, GoogleClientKind.Desktop, "desktop-id", "sec"),
            client(h, GoogleClientKind.Device, "device-id"),
        )
        val storage = InMemoryTokenStorage(
            OAuthTokens("old", "refresh", 1L, clientKind = GoogleClientKind.Desktop),
        )
        val drive = DriveHttpClient(h, storage, clients)
        assertEquals("new_access", drive.getValidToken())
        assertEquals("desktop-id", capturedClientId)
        assertEquals(GoogleClientKind.Desktop, storage.load()?.clientKind)
    }

    @Test
    fun refreshWithoutMatchingClientFailsWithAuthError() = runTest {
        val h = http()
        val clients = GoogleAuthClients(null, client(h, GoogleClientKind.Device, "device-id"))
        val storage = InMemoryTokenStorage(
            OAuthTokens("old", "refresh", 1L, clientKind = GoogleClientKind.Desktop),
        )
        assertFailsWith<RemoteAuthException> { DriveHttpClient(h, storage, clients).getValidToken() }
    }
}
