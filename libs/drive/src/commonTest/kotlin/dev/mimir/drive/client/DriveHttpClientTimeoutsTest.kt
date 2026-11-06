package dev.mimir.drive.client

import dev.mimir.drive.auth.FakeAccessTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriveHttpClientTimeoutsTest {
    private suspend fun timeoutsOfRequestThrough(injected: (MockEngine) -> HttpClient): HttpTimeoutConfig? {
        var timeouts: HttpTimeoutConfig? = null
        val engine = MockEngine { req ->
            timeouts = req.getCapabilityOrNull(HttpTimeoutCapability)
            respond("{}")
        }
        val drive = DriveHttpClient(injected(engine), FakeAccessTokenSource(ArrayDeque(listOf("t"))))
        drive.executeWithRetry { drive.httpClient.get("https://www.googleapis.com/drive/v3/files") }
        return timeouts
    }

    @Test
    fun requestsCarryConnectAndSocketTimeoutsButNoWholeRequestTimeout() = runTest {
        val t = assertNotNull(
            timeoutsOfRequestThrough { HttpClient(it) },
            "HttpTimeout must be installed: without it CIO cancels any call after 15 s",
        )
        assertEquals(10_000L, t.connectTimeoutMillis)
        assertEquals(30_000L, t.socketTimeoutMillis)
        assertTrue(t.requestTimeoutMillis == null || t.requestTimeoutMillis == HttpTimeoutConfig.INFINITE_TIMEOUT_MS)
    }

    @Test
    fun wholeRequestTimeoutOfTheInjectedClientIsLifted() = runTest {
        val t = assertNotNull(
            timeoutsOfRequestThrough { HttpClient(it) { install(HttpTimeout) { requestTimeoutMillis = 5_000 } } },
        )
        assertTrue(t.requestTimeoutMillis == null || t.requestTimeoutMillis == HttpTimeoutConfig.INFINITE_TIMEOUT_MS)
    }
}
