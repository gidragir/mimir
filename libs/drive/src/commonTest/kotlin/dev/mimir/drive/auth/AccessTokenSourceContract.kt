package dev.mimir.drive.auth

import dev.mimir.drive.client.DriveAuthException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Контракт любого [AccessTokenSource]; наследники задают вошедший и невошедший источники. */
abstract class AccessTokenSourceContract {
    abstract val storedToken: String
    abstract fun signedIn(): AccessTokenSource
    abstract fun signedOut(): AccessTokenSource

    @Test
    fun `token returns stored token while valid`() = runTest {
        val source = signedIn()
        assertEquals(storedToken, source.token())
        assertEquals(storedToken, source.token())
    }

    @Test
    fun `refresh returns a new token`() = runTest {
        val source = signedIn()
        val refreshed = source.refresh()
        assertNotEquals(storedToken, refreshed)
        assertEquals(refreshed, source.token())
    }

    @Test
    fun `signed out source throws DriveAuthException with 401`() = runTest {
        val source = signedOut()
        assertEquals(HttpStatusCode.Unauthorized, assertFailsWith<DriveAuthException> { source.token() }.statusCode)
        assertEquals(HttpStatusCode.Unauthorized, assertFailsWith<DriveAuthException> { source.refresh() }.statusCode)
    }

    @Test
    fun `hasCredentials reflects sign-in`() = runTest {
        assertTrue(signedIn().hasCredentials())
        assertFalse(signedOut().hasCredentials())
    }
}
