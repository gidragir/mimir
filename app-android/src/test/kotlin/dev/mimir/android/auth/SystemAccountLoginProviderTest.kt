package dev.mimir.android.auth

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemAccountLoginProviderTest {

    @Test
    fun `method is SystemAccount`() {
        val authorizer = FakeGisAuthorizer()
        val tokenSource = GisTokenSource(authorizer)
        val provider = SystemAccountLoginProvider(tokenSource) { "user@example.com" }

        assertEquals(DaemonLoginMethod.SystemAccount, provider.method)
    }

    @Test
    fun `start returns Started`() = runTest {
        val authorizer = FakeGisAuthorizer()
        val tokenSource = GisTokenSource(authorizer)
        val provider = SystemAccountLoginProvider(tokenSource) { "user@example.com" }

        val start = provider.start()
        assertEquals(DaemonLoginStart.Started, start)
    }

    @Test
    fun `poll returns Authorized with email`() = runTest {
        val authorizer = FakeGisAuthorizer(tokenResult = "valid-token")
        val tokenSource = GisTokenSource(authorizer)
        var passedToken: String? = null
        val provider = SystemAccountLoginProvider(tokenSource) { token ->
            passedToken = token
            "user@gmail.com"
        }

        provider.start()
        val poll = provider.poll()

        val authorized = assertIs<DaemonLoginPoll.Authorized>(poll)
        assertEquals("user@gmail.com", authorized.email)
        assertEquals("valid-token", passedToken)
    }

    @Test
    fun `poll returns Failed when silent authorization needs UI`() = runTest {
        val authorizer = FakeGisAuthorizer(tokenResult = null)
        val tokenSource = GisTokenSource(authorizer)
        val provider = SystemAccountLoginProvider(tokenSource) { "user@gmail.com" }

        provider.start()
        val poll = provider.poll()

        val failed = assertIs<DaemonLoginPoll.Failed>(poll)
        assertEquals("Не удалось войти в Google", failed.reason)
    }

    @Test
    fun `signOut revokes access`() = runTest {
        val authorizer = FakeGisAuthorizer(tokenResult = "token-1")
        val tokenSource = GisTokenSource(authorizer)
        val provider = SystemAccountLoginProvider(tokenSource) { "user@gmail.com" }

        provider.signOut()
        assertTrue(authorizer.revoked)
    }
}
