package dev.mimir.android.auth

import dev.mimir.drive.client.DriveAuthException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GisTokenSourceTest {
    private val initialTime = Instant.parse("2026-10-27T10:00:00Z")

    private class TestClock(var current: Instant) : Clock {
        override fun now(): Instant = current
        fun advance(duration: Duration) {
            current += duration
        }
    }

    @Test
    fun `token is cached for ttl`() = runTest {
        val clock = TestClock(initialTime)
        val authorizer = FakeGisAuthorizer(tokenResult = "cached-token")
        val source = GisTokenSource(authorizer, clock = clock, ttl = 50.minutes)

        assertEquals("cached-token", source.token())
        assertEquals(1, authorizer.authorizeCallCount)

        clock.advance(25.minutes)
        assertEquals("cached-token", source.token())
        assertEquals(1, authorizer.authorizeCallCount)
    }

    @Test
    fun `token re-authorizes silently after ttl`() = runTest {
        val clock = TestClock(initialTime)
        val authorizer = FakeGisAuthorizer(tokenResult = "token-1")
        val source = GisTokenSource(authorizer, clock = clock, ttl = 50.minutes)

        assertEquals("token-1", source.token())
        assertEquals(1, authorizer.authorizeCallCount)

        authorizer.tokenResult = "token-2"
        clock.advance(51.minutes)

        assertEquals("token-2", source.token())
        assertEquals(2, authorizer.authorizeCallCount)
    }

    @Test
    fun `refresh clears cached token and authorizes again`() = runTest {
        val clock = TestClock(initialTime)
        val authorizer = FakeGisAuthorizer(tokenResult = "token-1")
        val source = GisTokenSource(authorizer, clock = clock, ttl = 50.minutes)

        assertEquals("token-1", source.token())
        authorizer.tokenResult = "token-2"

        val refreshed = source.refresh()
        assertEquals("token-2", refreshed)
        assertEquals(listOf("token-1"), authorizer.clearedTokens)
        assertEquals(2, authorizer.authorizeCallCount)
        assertEquals("token-2", source.token())
    }

    @Test
    fun `token throws auth error when interactive resolution is required`() = runTest {
        val authorizer = FakeGisAuthorizer(tokenResult = null)
        val source = GisTokenSource(authorizer)

        val error = assertFailsWith<DriveAuthException> { source.token() }
        assertEquals(HttpStatusCode.Unauthorized, error.statusCode)

        val refreshError = assertFailsWith<DriveAuthException> { source.refresh() }
        assertEquals(HttpStatusCode.Unauthorized, refreshError.statusCode)
    }

    @Test
    fun `hasCredentials false when silent authorization needs UI`() = runTest {
        val authorizer = FakeGisAuthorizer(tokenResult = null)
        val source = GisTokenSource(authorizer)

        assertFalse(source.hasCredentials())
    }

    @Test
    fun `hasCredentials reflects sign-in`() = runTest {
        val authorizer = FakeGisAuthorizer(tokenResult = "token-ok")
        val source = GisTokenSource(authorizer)

        assertTrue(source.hasCredentials())
    }
}
