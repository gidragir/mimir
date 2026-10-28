package dev.mimir.daemon

import dev.mimir.runtime.FileTokenStorage

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import dev.mimir.client.DaemonLoginPoll
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConflictAndDeviceAuthRoutesTest {
    private val configDir = Files.createTempDirectory("mimir-routes-cfg")
    private val vaultDir = Files.createTempDirectory("mimir-routes-vault")
    private val conflictName = "Notes/Doc (conflicted copy 2026-10-24-120000).md"
    private var runtime: dev.mimir.runtime.MimirRuntime? = null
    private var googleReply: Pair<HttpStatusCode, String> = HttpStatusCode.OK to "{}"

    @AfterTest
    fun cleanup() {
        runtime?.close()
        configDir.toFile().deleteRecursively()
        vaultDir.toFile().deleteRecursively()
    }

    private fun mockGoogle() = HttpClient(
        MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/device/code") ->
                    HttpStatusCode.OK to """{"device_code":"dev_code_123","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"""
                request.url.encodedPath.endsWith("/userinfo") ->
                    HttpStatusCode.OK to """{"email":"user@gmail.com","name":"User"}"""
                else -> googleReply
            }
            respond(body.second, body.first, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun ApplicationTestBuilder.serve(withVault: Boolean = true, clientId: String? = "cid") {
        val config = DaemonConfig(
            vaultPath = vaultDir.toString().takeIf { withVault },
            googleDeviceClientId = clientId,
            configDir = configDir.toString(),
        )
        val runtime = createRuntime(config, httpClient = mockGoogle(), conflictScanInterval = 50.milliseconds).also { runtime = it }
        runBlocking { runtime.initialize() }
        application { mimirRoutes(runtime, config) }
    }

    private fun put(relative: String, text: String) {
        val file = vaultDir.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.resolve(original: String, conflict: String, resolution: String) =
        client.post("/api/v1/conflicts/resolve") {
            contentType(ContentType.Application.Json)
            setBody("""{"originalPath":"$original","conflictPath":"$conflict","resolution":"$resolution"}""")
        }

    @Test
    fun `conflicts lists conflicted copies with their originals`() = testApplication {
        serve()
        put("Notes/Doc.md", "mine")
        put(conflictName, "theirs")
        put("Notes/Other.md", "plain")

        val body = client.get("/api/v1/conflicts").json()

        val list: JsonArray = body.getValue("conflicts").jsonArray
        assertEquals(1, list.size)
        val item = list.single().jsonObject
        assertEquals("Notes/Doc.md", item.getValue("originalPath").jsonPrimitive.content)
        assertEquals(conflictName, item.getValue("conflictPath").jsonPrimitive.content)
        assertEquals("2026-10-24T12:00:00Z", item.getValue("createdAt").jsonPrimitive.content)
    }

    @Test
    fun `status counts active conflicts`() = testApplication {
        serve()
        put("Notes/Doc.md", "mine")
        put(conflictName, "theirs")

        val count = withTimeout(5_000) {
            var current = client.get("/api/v1/status").json().getValue("activeConflicts").jsonPrimitive.int
            while (current != 1) {
                delay(50)
                current = client.get("/api/v1/status").json().getValue("activeConflicts").jsonPrimitive.int
            }
            current
        }
        assertEquals(1, count)
    }

    @Test
    fun `keep_original deletes the conflicted copy only`() = testApplication {
        serve()
        put("Notes/Doc.md", "mine")
        put(conflictName, "theirs")

        val response = resolve("Notes/Doc.md", conflictName, "keep_original")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"resolved","path":"Notes/Doc.md"}""", response.bodyAsText())
        assertEquals("mine", vaultDir.resolve("Notes/Doc.md").readText())
        assertFalse(vaultDir.resolve(conflictName).exists())
    }

    @Test
    fun `keep_conflict replaces the original and removes the copy`() = testApplication {
        serve()
        put("Notes/Doc.md", "mine")
        put(conflictName, "theirs")

        val response = resolve("Notes/Doc.md", conflictName, "keep_conflict")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("theirs", vaultDir.resolve("Notes/Doc.md").readText())
        assertFalse(vaultDir.resolve(conflictName).exists())
        assertEquals(listOf("Doc.md"), vaultDir.resolve("Notes").toFile().list()!!.toList())
    }

    @Test
    fun `resolve rejects bad requests without touching files`() = testApplication {
        serve()
        put("Notes/Doc.md", "mine")
        put(conflictName, "theirs")
        put("Notes/Other.md", "other")

        assertEquals(HttpStatusCode.NotFound, resolve("Notes/Doc.md", "Notes/Doc (conflicted copy 2030-01-01-000000).md", "keep_original").status)
        assertEquals(HttpStatusCode.BadRequest, resolve("Notes/Other.md", conflictName, "keep_conflict").status)
        assertEquals(HttpStatusCode.BadRequest, resolve("Notes/Doc.md", conflictName, "merge").status)
        assertEquals(HttpStatusCode.BadRequest, resolve("../Doc.md", "../Doc (conflicted copy 2026-10-24-120000).md", "keep_conflict").status)
        assertEquals(HttpStatusCode.BadRequest, resolve("Notes/Other.md", "Notes/Other.md", "keep_conflict").status)

        assertEquals("mine", vaultDir.resolve("Notes/Doc.md").readText())
        assertEquals("other", vaultDir.resolve("Notes/Other.md").readText())
        assertTrue(vaultDir.resolve(conflictName).exists())
    }

    @Test
    fun `without a vault conflicts are empty and resolve is a precondition failure`() = testApplication {
        serve(withVault = false)

        assertEquals("[]", client.get("/api/v1/conflicts").json().getValue("conflicts").toString())
        assertEquals(HttpStatusCode.PreconditionFailed, resolve("a.md", "a (conflicted copy 2026-10-24-120000).md", "keep_original").status)
    }

    @Test
    fun `device start returns the verification data`() = testApplication {
        serve()

        val response = client.post("/api/v1/auth/device/start")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals("ABCD-EFGH", body.getValue("user_code").jsonPrimitive.content)
        assertEquals("https://www.google.com/device", body.getValue("verification_url").jsonPrimitive.content)
        assertEquals("dev_code_123", body.getValue("device_code").jsonPrimitive.content)
        assertEquals(1800, body.getValue("expires_in").jsonPrimitive.int)
        assertEquals(5, body.getValue("interval").jsonPrimitive.int)
    }

    @Test
    fun `device start without a google client id is a precondition failure`() = testApplication {
        serve(clientId = null)

        assertEquals(HttpStatusCode.PreconditionFailed, client.post("/api/v1/auth/device/start").status)
    }

    @Test
    fun `device poll is pending until the user confirms`() = testApplication {
        serve()
        client.post("/api/v1/auth/device/start")
        googleReply = HttpStatusCode.fromValue(428) to """{"error":"authorization_pending"}"""

        val response = client.get("/api/v1/auth/device/poll?device_code=dev_code_123")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"pending"}""", response.bodyAsText())
    }

    @Test
    fun `device poll stores tokens once authorized`() = testApplication {
        serve()
        client.post("/api/v1/auth/device/start")
        googleReply = HttpStatusCode.OK to """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}"""

        val response = client.get("/api/v1/auth/device/poll?device_code=dev_code_123")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"authorized","email":"user@gmail.com"}""", response.bodyAsText())
        val saved = assertNotNull(FileTokenStorage(configDir.resolve("tokens.json")).let { runBlocking { it.load() } })
        assertEquals("rt", saved.refreshToken)
        assertEquals("user@gmail.com", client.get("/api/v1/status").json().getValue("accountEmail").jsonPrimitive.content)
        assertEquals("device_code", Json.parseToJsonElement(configDir.resolve("account.json").readText()).jsonObject.getValue("method").jsonPrimitive.content)
    }

    @Test
    fun `device poll reports an expired code`() = testApplication {
        serve()
        client.post("/api/v1/auth/device/start")
        googleReply = HttpStatusCode.BadRequest to """{"error":"expired_token"}"""

        val response = client.get("/api/v1/auth/device/poll?device_code=dev_code_123")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"status":"expired","error":"Code has expired"}""", response.bodyAsText())
        assertFalse(configDir.resolve("tokens.json").exists())
    }

    @Test
    fun `device poll requires a device code`() = testApplication {
        serve()

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/auth/device/poll").status)
    }

    private fun ApplicationTestBuilder.serveFake(fake: FakeDaemonService) {
        application { mimirRoutes(fake, DaemonConfig()) }
    }

    @Test
    fun authMethodsListsAvailable() = testApplication {
        val fake = FakeDaemonService().apply { methods = setOf(LoginMethod.DeviceCode, LoginMethod.Browser) }
        serveFake(fake)

        assertEquals("""{"methods":["browser","device_code"]}""", client.get("/api/v1/auth/methods").bodyAsText())
        fake.methods = setOf(LoginMethod.Browser, LoginMethod.SystemAccount)
        assertEquals("""{"methods":["system_account","browser"]}""", client.get("/api/v1/auth/methods").bodyAsText())
        fake.methods = emptySet()
        assertEquals("""{"methods":[]}""", client.get("/api/v1/auth/methods").bodyAsText())
    }

    @Test
    fun browserStartReturnsUrl() = testApplication {
        serveFake(FakeDaemonService().apply { browserUrl = "https://accounts.example/auth?x=1" })

        val response = client.post("/api/v1/auth/browser/start")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("https://accounts.example/auth?x=1", response.json().getValue("authUrl").jsonPrimitive.content)
    }

    @Test
    fun browserStartConflictWhenUnavailable() = testApplication {
        serveFake(FakeDaemonService().apply { browserUrl = null })

        val response = client.post("/api/v1/auth/browser/start")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"error":"Browser sign-in is not available"}""", response.bodyAsText())
    }

    @Test
    fun browserPollMapsAllStates() = testApplication {
        val fake = FakeDaemonService()
        serveFake(fake)

        assertEquals("""{"status":"pending"}""", client.get("/api/v1/auth/browser/poll").bodyAsText())
        fake.loginPoll = DaemonLoginPoll.Authorized("u@x")
        assertEquals("""{"status":"authorized","email":"u@x"}""", client.get("/api/v1/auth/browser/poll").bodyAsText())
        fake.loginPoll = DaemonLoginPoll.Failed("Вход отменён")
        assertEquals("""{"status":"failed","error":"Вход отменён"}""", client.get("/api/v1/auth/browser/poll").bodyAsText())
    }
}
