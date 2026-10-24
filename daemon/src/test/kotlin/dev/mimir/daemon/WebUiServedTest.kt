package dev.mimir.daemon

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebUiServedTest {
    @Test
    fun `GET root returns index html with Mimir title`() = testApplication {
        application { mimirRoutes(FakeDaemonService(), DaemonConfig()) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        assertTrue("<title>Mimir</title>" in response.bodyAsText())
    }
}
