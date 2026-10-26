package dev.mimir.daemon

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorsSupportTest {
    @Test
    fun testOptionsPreflightReturnsCorsHeadersForBrowserExtension() = testApplication {
        application { mimirRoutes(FakeDaemonService(), DaemonConfig()) }
        val response = client.options("/api/v1/status") {
            header(HttpHeaders.Origin, "chrome-extension://abcdefghijklmnop")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, "Authorization, X-Mimir-Token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.AccessControlAllowOrigin] != null)
    }
}
