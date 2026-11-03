package dev.mimir.daemon

import dev.mimir.core.BuildConfig
import dev.mimir.core.SyncStatus
import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class UpdateRoutesTest {
    private val service = FakeDaemonService()
    private val updates = FakeUpdateManager()
    private val version = BuildConfig.VERSION

    private fun ApplicationTestBuilder.serve(
        config: DaemonConfig = DaemonConfig(),
        manager: dev.mimir.core.update.UpdateManager? = updates,
    ) = application { mimirRoutes(service, config, manager) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `get returns current version and idle status with every spec field`() = testApplication {
        updates.set(UpdateState.Idle(Instant.parse("2026-11-02T00:00:00Z"), SemVer.parse("0.1.0")))
        serve()

        val response = client.get("/api/v1/update")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals(version, body.str("currentVersion"))
        assertEquals("IDLE", body.str("status"))
        assertEquals("0.1.0", body.str("latestVersion"))
        assertEquals("2026-11-02T00:00:00Z", body.str("lastCheckedAt"))
        listOf(
            "currentVersion", "status", "latestVersion", "releaseNotes", "publishedAt", "capability",
            "downloadProgress", "htmlUrl", "errorMessage", "lastCheckedAt",
        ).forEach { assertTrue(it in body, "missing $it in $body") }
        assertEquals(JsonNull, body["errorMessage"])
    }

    @Test
    fun `get maps every update state`() = testApplication {
        serve()
        val ready = UpdateState.ReadyToInstall(
            testRelease,
            DownloadedAsset.LocalFile("/home/user/.cache/mimir-update/mimir.msi", "abc", testAsset),
            ApplierCapability.DOWNLOAD_AND_LAUNCH,
        )
        val cases = listOf(
            UpdateState.Checking to "CHECKING",
            updates.available() to "AVAILABLE",
            UpdateState.Downloading(testRelease, testAsset, 0.5f, 5, 10) to "DOWNLOADING",
            ready to "READY_TO_INSTALL",
            UpdateState.Installing(testRelease) to "INSTALLING",
            UpdateState.Error("boom", updates.available()) to "ERROR",
        )
        for ((state, expected) in cases) {
            updates.set(state)
            val body = client.get("/api/v1/update").json()
            assertEquals(expected, body.str("status"), "state $state")
            assertEquals(version, body.str("currentVersion"))
        }

        updates.set(updates.available(ApplierCapability.NOTIFY_ONLY))
        val available = client.get("/api/v1/update").json()
        assertEquals("1.2.3", available.str("latestVersion"))
        assertEquals("Fixes", available.str("releaseNotes"))
        assertEquals("2026-11-01T10:00:00Z", available.str("publishedAt"))
        assertEquals("NOTIFY_ONLY", available.str("capability"))
        assertEquals("https://example.test/releases/v1.2.3", available.str("htmlUrl"))

        updates.set(UpdateState.Downloading(testRelease, testAsset, 0.5f, 5, 10))
        assertEquals(0.5f, client.get("/api/v1/update").json().getValue("downloadProgress").jsonPrimitive.float)

        updates.set(UpdateState.Error("Download failed", updates.available()))
        val error = client.get("/api/v1/update").json()
        assertEquals("Download failed", error.str("errorMessage"))
        assertEquals("1.2.3", error.str("latestVersion"))

        updates.set(ready)
        val readyText = client.get("/api/v1/update").bodyAsText()
        assertFalse("mimir-update" in readyText || "/home/user" in readyText, "local path leaked: $readyText")
    }

    @Test
    fun `no manager or disabled config yields DISABLED and rejects commands without calling the manager`() = testApplication {
        serve(config = DaemonConfig(updateCheck = false))
        val body = client.get("/api/v1/update").json()
        assertEquals("DISABLED", body.str("status"))
        assertEquals(version, body.str("currentVersion"))
        for (action in listOf("check", "download", "apply")) {
            val response = client.post("/api/v1/update/$action")
            assertEquals(HttpStatusCode.Conflict, response.status, action)
            assertTrue(response.json().str("error").contains("disabled", ignoreCase = true))
        }
        assertEquals(0, updates.checkCalls + updates.downloadCalls + updates.applyCalls)
    }

    @Test
    fun `null manager is DISABLED`() = testApplication {
        serve(manager = null)
        assertEquals("DISABLED", client.get("/api/v1/update").json().str("status"))
    }

    @Test
    fun `DisabledUpdateManager is reported as DISABLED rather than an error`() = testApplication {
        serve(manager = dev.mimir.runtime.update.DisabledUpdateManager)
        assertEquals("DISABLED", client.get("/api/v1/update").json().str("status"))
    }

    @Test
    fun `check forces a check and returns the new status`() = testApplication {
        serve()
        updates.checkResult = UpdateCheckResult.UpdateAvailable(testRelease, testAsset)
        updates.set(updates.available())

        val response = client.post("/api/v1/update/check")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("AVAILABLE", response.json().str("status"))
        assertEquals(1, updates.checkCalls)
        assertEquals(true, updates.checkForced)
    }

    @Test
    fun `check failure is structured json with 502 and retry-after`() = testApplication {
        serve()
        updates.checkResult = UpdateCheckResult.Error("GitHub rate limit", RuntimeException("stack"), retryAfterSeconds = 60)

        val response = client.post("/api/v1/update/check")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("""{"error":"GitHub rate limit"}""", response.bodyAsText())
        assertEquals("60", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `download returns 202 immediately and the client polls until ready`() = testApplication {
        serve()
        val gate = CompletableDeferred<Unit>()
        updates.downloadGate = gate
        updates.set(updates.available())

        val response = client.post("/api/v1/update/download")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"status":"started"}""", response.bodyAsText())
        // Ответ пришёл, а скачивание ещё идёт.
        assertEquals("DOWNLOADING", client.get("/api/v1/update").json().str("status"))
        // Повторный запрос во время скачивания не запускает второе.
        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/update/download").status)

        gate.complete(Unit)
        var status = ""
        repeat(50) {
            status = client.get("/api/v1/update").json().str("status")
            if (status == "READY_TO_INSTALL") return@repeat
            delay(20)
        }
        assertEquals("READY_TO_INSTALL", status)
        assertEquals(1, updates.downloadCalls)
    }

    @Test
    fun `download failure surfaces as ERROR state on poll without a stack trace`() = testApplication {
        serve()
        updates.downloadResult = Result.failure(IllegalStateException("Checksum mismatch"))
        updates.set(updates.available())

        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/update/download").status)

        var body = client.get("/api/v1/update").json()
        repeat(50) {
            if (body.str("status") == "ERROR") return@repeat
            delay(20)
            body = client.get("/api/v1/update").json()
        }
        assertEquals("ERROR", body.str("status"))
        assertEquals("Checksum mismatch", body.str("errorMessage"))
    }

    @Test
    fun `download in the wrong state is 409 with a human message`() = testApplication {
        serve()
        for (state in listOf(UpdateState.Idle(), UpdateState.Checking, UpdateState.Installing(testRelease))) {
            updates.set(state)
            val response = client.post("/api/v1/update/download")
            assertEquals(HttpStatusCode.Conflict, response.status, "$state")
            assertEquals("No update is available to download", response.json().str("error"))
        }
        assertEquals(0, updates.downloadCalls)
    }

    @Test
    fun `download without an installable asset is 409`() = testApplication {
        serve()
        updates.set(UpdateState.Available(testRelease, null, ApplierCapability.NOTIFY_ONLY))

        val response = client.post("/api/v1/update/download")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(0, updates.downloadCalls)
    }

    @Test
    fun `apply launches the installer and returns the result`() = testApplication {
        serve()
        updates.set(UpdateState.ReadyToInstall(testRelease, DownloadedAsset.Virtual(testAsset), ApplierCapability.DOWNLOAD_AND_LAUNCH))

        val response = client.post("/api/v1/update/apply")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals("installer_launched", body.str("result"))
        assertEquals("Installer started", body.str("message"))
        assertTrue(body.getValue("requiresRestart").jsonPrimitive.boolean)
        assertEquals(1, updates.applyCalls)
    }

    @Test
    fun `apply notification result carries the url`() = testApplication {
        serve()
        updates.applyResult = UpdateApplyResult.NotificationDispatched("https://example.test/releases/v1.2.3")
        updates.set(UpdateState.ReadyToInstall(testRelease, DownloadedAsset.Virtual(testAsset), ApplierCapability.NOTIFY_ONLY))

        val body = client.post("/api/v1/update/apply").json()

        assertEquals("notification", body.str("result"))
        assertEquals("https://example.test/releases/v1.2.3", body.str("url"))
    }

    @Test
    fun `apply failure is structured json`() = testApplication {
        serve()
        updates.applyResult = UpdateApplyResult.Failed("msiexec not found", IllegalStateException("trace"))
        updates.set(UpdateState.ReadyToInstall(testRelease, DownloadedAsset.Virtual(testAsset), ApplierCapability.DOWNLOAD_AND_LAUNCH))

        val response = client.post("/api/v1/update/apply")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("""{"error":"msiexec not found"}""", response.bodyAsText())
    }

    @Test
    fun `apply in the wrong state is 409`() = testApplication {
        serve()
        updates.set(updates.available())

        val response = client.post("/api/v1/update/apply")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("No downloaded update to install", response.json().str("error"))
        assertEquals(0, updates.applyCalls)
    }

    @Test
    fun `apply is rejected while a sync run is in progress`() = testApplication {
        serve()
        service.status.value = SyncStatus.Syncing
        updates.set(UpdateState.ReadyToInstall(testRelease, DownloadedAsset.Virtual(testAsset), ApplierCapability.DOWNLOAD_AND_LAUNCH))

        val response = client.post("/api/v1/update/apply")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.json().str("error").contains("sync", ignoreCase = true))
        assertEquals(0, updates.applyCalls)

        service.status.value = SyncStatus.Idle
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/update/apply").status)
    }

    @Test
    fun `update routes require the token when one is configured`() = testApplication {
        serve(config = DaemonConfig(token = "secret123"))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/update").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/update/apply").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/update") { header(HttpHeaders.Authorization, "Bearer secret123") }.status,
        )
    }

    @Test
    fun `update commands from an arbitrary web origin are forbidden even without a token`() = testApplication {
        serve()
        updates.set(updates.available())

        for (action in listOf("check", "download", "apply")) {
            val response = client.post("/api/v1/update/$action") { header(HttpHeaders.Origin, "https://evil.example") }
            assertEquals(HttpStatusCode.Forbidden, response.status, action)
        }
        assertEquals(0, updates.checkCalls + updates.downloadCalls + updates.applyCalls)

        val extension = client.post("/api/v1/update/check") { header(HttpHeaders.Origin, "chrome-extension://abcdef") }
        assertEquals(HttpStatusCode.OK, extension.status)
        // Встроенный web-ui: тот же источник, что и Host (CORS-плагин Ktor пропускает same-origin).
        val sameOrigin = client.post("/api/v1/update/check") {
            header(HttpHeaders.Host, "127.0.0.1:47821")
            header(HttpHeaders.Origin, "http://127.0.0.1:47821")
        }
        assertEquals(HttpStatusCode.OK, sameOrigin.status)
    }
}
