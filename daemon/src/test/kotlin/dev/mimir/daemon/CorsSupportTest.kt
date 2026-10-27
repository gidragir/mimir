package dev.mimir.daemon

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun preflightFromExtensionAllowsMethodsAndHeaders() = testApplication {
        application { mimirRoutes(FakeDaemonService(), DaemonConfig()) }
        val response = client.options("/api/v1/vault") {
            header(HttpHeaders.Origin, "chrome-extension://abcdefghijklmnop")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "Authorization, Content-Type")
        }
        assertEquals("chrome-extension://abcdefghijklmnop", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(response.headers[HttpHeaders.AccessControlAllowMethods]!!.contains("POST"))
        assertTrue(response.headers[HttpHeaders.AccessControlAllowHeaders]!!.contains("Authorization", ignoreCase = true))
    }

    @Test
    fun preflightFromObsidianAppIsAllowed() = testApplication {
        application { mimirRoutes(FakeDaemonService(), DaemonConfig()) }
        val response = client.options("/api/v1/status") {
            header(HttpHeaders.Origin, "app://obsidian.md")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals("app://obsidian.md", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun foreignWebOriginIsNotAllowed() = testApplication {
        application { mimirRoutes(FakeDaemonService(), DaemonConfig()) }
        val response = client.options("/api/v1/vault") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "Content-Type")
        }
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials])
    }
}
