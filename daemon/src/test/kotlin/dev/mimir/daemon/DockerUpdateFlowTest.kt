package dev.mimir.daemon

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DefaultUpdateManager
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.GitHubReleasesChannel
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpResponse
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateManager
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** Реальные DefaultUpdateManager + GitHubReleasesChannel (поддельный HTTP) + аппликер демона, без мока менеджера. */
class DockerUpdateFlowTest {
    private val api = "https://api.github.com/repos/o/r/releases/latest"
    private val releaseJson =
        """{"tag_name":"v99.0.0","body":"n","published_at":"2026-11-01T10:00:00Z","html_url":"https://gh.example/rel/v99.0.0","assets":[]}"""

    private val http = object : UpdateHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>) =
            if (url == api) UpdateHttpResponse(200, emptyMap(), releaseJson) else UpdateHttpResponse(404, emptyMap(), "")

        override suspend fun downloadStream(url: String, headers: Map<String, String>, onChunk: suspend (ByteArray, Long, Long) -> Unit) =
            Result.failure<Unit>(IllegalStateException("not used"))
    }

    private fun manager(applier: UpdateApplier): UpdateManager =
        DefaultUpdateManager(SemVer.parse("0.1.0"), TargetPlatform.DOCKER_ANY, GitHubReleasesChannel("o", "r", http), applier)

    private fun ApplicationTestBuilder.serve(manager: UpdateManager) =
        application { mimirRoutes(FakeDaemonService(), DaemonConfig(), manager) }

    private suspend fun HttpResponse.status(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.awaitStatus(expected: String) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (client.get("/api/v1/update").status() != expected) delay(10) }
    }

    /** Аппликер с воротами и счётчиками вызовов. */
    private class GatedApplier : UpdateApplier {
        override val capability = ApplierCapability.NOTIFY_ONLY
        val downloads = AtomicInteger()
        val applies = AtomicInteger()
        val downloadGate = CompletableDeferred<Unit>()
        val applyGate = CompletableDeferred<Unit>()

        override suspend fun download(asset: UpdateAsset, release: UpdateRelease, onProgress: (Float, Long, Long) -> Unit): Result<DownloadedAsset> {
            downloads.incrementAndGet()
            downloadGate.await()
            return Result.success(DownloadedAsset.Virtual(asset))
        }

        override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
            applies.incrementAndGet()
            applyGate.await()
            return UpdateApplyResult.NotificationDispatched("https://gh.example/rel/v99.0.0")
        }
    }

    @Test
    fun `docker check download apply works end to end and logs the release url`() = testApplication {
        val logged = mutableListOf<String>()
        serve(manager(DockerLogNotifyApplier(log = { logged += it })))

        val check = client.get("/api/v1/update").also { assertEquals("IDLE", it.status()) }
        assertEquals(HttpStatusCode.OK, check.status)
        val checked = client.post("/api/v1/update/check")
        assertEquals(HttpStatusCode.OK, checked.status)
        val body = Json.parseToJsonElement(checked.bodyAsText()).jsonObject
        assertEquals("AVAILABLE", body.getValue("status").jsonPrimitive.content)
        assertEquals("NOTIFY_ONLY", body.getValue("capability").jsonPrimitive.content)

        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/update/download").status)
        awaitStatus("READY_TO_INSTALL")

        val applied = client.post("/api/v1/update/apply")
        assertEquals(HttpStatusCode.OK, applied.status)
        val result = Json.parseToJsonElement(applied.bodyAsText()).jsonObject
        assertEquals("notification", result.getValue("result").jsonPrimitive.content)
        assertEquals("https://gh.example/rel/v99.0.0", result.getValue("url").jsonPrimitive.content)
        assertEquals(listOf("Update v99.0.0 available: https://gh.example/rel/v99.0.0"), logged)
    }

    @Test
    fun `two concurrent downloads start exactly one applier download`() = testApplication {
        val applier = GatedApplier()
        serve(manager(applier))
        client.post("/api/v1/update/check")

        val statuses = withContext(Dispatchers.Default) {
            List(2) { async { client.post("/api/v1/update/download").status } }.awaitAll()
        }
        applier.downloadGate.complete(Unit)

        assertEquals(listOf(HttpStatusCode.Accepted, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
        awaitStatus("READY_TO_INSTALL")
        assertEquals(1, applier.downloads.get())
    }

    @Test
    fun `two concurrent applies run exactly one applier apply`() = testApplication {
        val applier = GatedApplier()
        applier.downloadGate.complete(Unit)
        serve(manager(applier))
        client.post("/api/v1/update/check")
        client.post("/api/v1/update/download")
        awaitStatus("READY_TO_INSTALL")

        val statuses = withContext(Dispatchers.Default) {
            val first = async { client.post("/api/v1/update/apply").status }
            withTimeout(10_000) { while (applier.applies.get() == 0) delay(5) }
            val second = client.post("/api/v1/update/apply").status
            applier.applyGate.complete(Unit)
            listOf(first.await(), second)
        }

        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
        assertEquals(1, applier.applies.get())
    }
}
