package dev.mimir.daemon

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultRoutesTest {
    @Test
    fun `inspect vault returns 403 when the daemon is locked`() = testApplication {
        val service = FakeDaemonService().apply { isLocked = true }
        application { mimirRoutes(service, DaemonConfig()) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/vault/inspect").status)
    }

    @Test
    fun `inspect vault returns counts and requiresStrategyConfirmation`() = testApplication {
        val service = FakeDaemonService().apply {
            isLocked = false
            inspection = VaultInspectionDto(localFileCount = 5, remoteFileCount = 8, requiresStrategyConfirmation = true)
        }
        application { mimirRoutes(service, DaemonConfig()) }
        val response = client.get("/api/v1/vault/inspect")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("5", json["localFileCount"]?.jsonPrimitive?.content)
        assertEquals("8", json["remoteFileCount"]?.jsonPrimitive?.content)
        assertEquals("true", json["requiresStrategyConfirmation"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST vault responds with the path and the local file count`() = testApplication {
        val service = FakeDaemonService().apply { fileCount = 42 }
        application { mimirRoutes(service, DaemonConfig()) }
        val response = client.post("/api/v1/vault") { contentType(ContentType.Application.Json); setBody("""{"vaultPath":"/ok"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("/ok", json["vaultPath"]?.jsonPrimitive?.content)
        assertEquals("42", json["fileCount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST vault configures the vault and maps bad paths to 400`() = testApplication {
        val service = FakeDaemonService()
        application { mimirRoutes(service, DaemonConfig()) }
        val ok = client.post("/api/v1/vault") { contentType(ContentType.Application.Json); setBody("""{"vaultPath":"/ok"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("/ok", service.vaultPath)
        service.rejectVault = true
        val bad = client.post("/api/v1/vault") { contentType(ContentType.Application.Json); setBody("""{"vaultPath":"/bad"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }
}
