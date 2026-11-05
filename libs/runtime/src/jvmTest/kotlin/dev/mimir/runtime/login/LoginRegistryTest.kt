package dev.mimir.runtime.login

import dev.mimir.client.DaemonLoginMethod
import dev.mimir.client.DaemonLoginPoll
import dev.mimir.client.DaemonLoginStart
import dev.mimir.drive.auth.AccessTokenSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LoginRegistryTest {
    private class StubProvider(override val method: DaemonLoginMethod) : LoginProvider {
        override val tokenSource: AccessTokenSource get() = error("not used")
        override suspend fun start(): DaemonLoginStart = DaemonLoginStart.Started
        override suspend fun poll(): DaemonLoginPoll = DaemonLoginPoll.Pending
        override suspend fun signOut() = Unit
    }

    @Test
    fun `methods lists registered providers`() {
        val registry = LoginRegistry(listOf(StubProvider(DaemonLoginMethod.DeviceCode), StubProvider(DaemonLoginMethod.Browser)))

        assertEquals(setOf(DaemonLoginMethod.Browser, DaemonLoginMethod.DeviceCode), registry.methods())
        assertEquals(emptySet(), LoginRegistry(emptyList()).methods())
    }

    @Test
    fun `get returns null for unregistered method`() {
        val browser = StubProvider(DaemonLoginMethod.Browser)
        val registry = LoginRegistry(listOf(browser))

        assertSame(browser, registry[DaemonLoginMethod.Browser])
        assertNull(registry[DaemonLoginMethod.SystemAccount])
        assertNull(registry[DaemonLoginMethod.DeviceCode])
    }
}
