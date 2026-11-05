package dev.mimir.drive.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrowserLoginTest {
    private val client = GoogleAuthClient(
        HttpClient(MockEngine { respond("") }),
        GoogleClientCredentials(GoogleClientKind.Device, "test-client-id", null),
    )

    @Test
    fun challengeMatchesRfc7636Vector() =
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

    @Test
    fun verifierIsUrlSafeAndLongEnough() {
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test
    fun verifiersDiffer() = assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())

    @Test
    fun authUrlUsesLoopbackRedirectAndS256() {
        val r = client.newBrowserLogin(5123)
        assertEquals("http://127.0.0.1:5123/callback", r.redirectUri)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A5123%2Fcallback" in r.authUrl || "redirect_uri=http://127.0.0.1:5123/callback" in r.authUrl)
        assertTrue("code_challenge_method=S256" in r.authUrl)
        assertTrue("code_challenge=${Pkce.challenge(r.verifier)}" in r.authUrl)
        assertTrue("state=${r.state}" in r.authUrl)
    }

    @Test
    fun callbackWithCodeAndMatchingState() =
        assertEquals(LoginCallback.Code("abc"), parseLoginCallback(mapOf("code" to "abc", "state" to "s"), "s"))

    @Test
    fun callbackAccessDenied() =
        assertEquals(LoginCallback.Denied, parseLoginCallback(mapOf("error" to "access_denied", "state" to "s"), "s"))

    @Test
    fun callbackWrongStateIsInvalid() {
        assertIs<LoginCallback.Invalid>(parseLoginCallback(mapOf("code" to "abc", "state" to "x"), "s"))
    }

    @Test
    fun callbackWithoutCodeIsInvalid() {
        assertIs<LoginCallback.Invalid>(parseLoginCallback(mapOf("state" to "s"), "s"))
    }
}
