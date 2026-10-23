package dev.mimir.daemon

import dev.mimir.core.StubSyncEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesTest {
    @Test
    fun `health returns ok and version`() = testApplication {
        application { mimirRoutes(StubSyncEngine()) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok","version":"0.1.0"}""", response.bodyAsText())
    }

    @Test
    fun `status returns not connected label`() = testApplication {
        application { mimirRoutes(StubSyncEngine()) }
        val response = client.get("/api/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"Mimir: not connected"}""", response.bodyAsText())
    }
}
