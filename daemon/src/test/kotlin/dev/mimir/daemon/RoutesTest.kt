package dev.mimir.daemon

import dev.mimir.core.SyncStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RoutesTest {
    private val service = FakeDaemonService()

    private fun ApplicationTestBuilder.serve(token: String? = null) =
        application { mimirRoutes(service, DaemonConfig(token = token)) }

    private suspend fun io.ktor.client.statement.HttpResponse.json(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `health returns ok and version`() = testApplication {
        serve()
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok","version":"0.1.0"}""", response.bodyAsText())
    }

    @Test
    fun `api requires the token when one is configured`() = testApplication {
        serve(token = "secret123")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/status").status)
        assertEquals("""{"error":"Unauthorized"}""", client.get("/api/v1/status").bodyAsText())
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/status") { header(HttpHeaders.Authorization, "Bearer nope") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/status") { header(HttpHeaders.Authorization, "Bearer secret123") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/status") { header("X-Mimir-Token", "secret123") }.status,
        )
        service.syncAccepted = true
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/sync").status)
        assertEquals(0, service.syncCalls, "handler must not run for unauthorized calls")
    }

    @Test
    fun `health stays open when a token is configured`() = testApplication {
        serve(token = "secret123")
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `api is open without a configured token`() = testApplication {
        serve()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/status").status)
    }

    @Test
    fun `status reports label, vault, last sync and lock state`() = testApplication {
        serve()

        val before = client.get("/api/v1/status").json()
        assertEquals("not_connected", before.getValue("status").jsonPrimitive.content)
        assertEquals("Mimir: not connected", before.getValue("label").jsonPrimitive.content)
        assertEquals(JsonNull, before.getValue("vaultPath"))
        assertEquals(JsonNull, before.getValue("lastSyncAt"))
        assertEquals(true, before.getValue("isLocked").jsonPrimitive.boolean)

        service.status.value = SyncStatus.Idle
        service.vaultPath = "/home/u/vault"
        service.isLocked = false
        service.lastSyncTime = Instant.parse("2026-10-24T12:00:00Z")
        val after = client.get("/api/v1/status").json()
        assertEquals("idle", after.getValue("status").jsonPrimitive.content)
        assertEquals("Mimir: up to date", after.getValue("label").jsonPrimitive.content)
        assertEquals("/home/u/vault", after.getValue("vaultPath").jsonPrimitive.content)
        assertEquals("2026-10-24T12:00:00Z", after.getValue("lastSyncAt").jsonPrimitive.content)
        assertEquals(false, after.getValue("isLocked").jsonPrimitive.boolean)
    }

    @Test
    fun `sync answers 202, 409 and 412`() = testApplication {
        serve()

        service.syncAccepted = true
        val started = client.post("/api/v1/sync")
        assertEquals(HttpStatusCode.Accepted, started.status)
        assertEquals("""{"status":"started"}""", started.bodyAsText())

        service.syncAccepted = false
        val busy = client.post("/api/v1/sync")
        assertEquals(HttpStatusCode.Conflict, busy.status)
        assertEquals("""{"status":"already_running"}""", busy.bodyAsText())

        service.syncAccepted = null
        val notConfigured = client.post("/api/v1/sync")
        assertEquals(HttpStatusCode.PreconditionFailed, notConfigured.status)
        assertEquals(
            """{"error":"Vault path, encryption password or cloud storage not configured"}""",
            notConfigured.bodyAsText(),
        )
    }

    @Test
    fun `unlock passes the password to the service`() = testApplication {
        serve()

        val response = client.post("/api/v1/unlock") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"secret_vault_password"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"unlocked"}""", response.bodyAsText())
        assertEquals("secret_vault_password", service.unlockedWith)
    }

    @Test
    fun `unlock rejects an empty password`() = testApplication {
        serve()

        val response = client.post("/api/v1/unlock") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(service.unlockedWith)
    }
}
