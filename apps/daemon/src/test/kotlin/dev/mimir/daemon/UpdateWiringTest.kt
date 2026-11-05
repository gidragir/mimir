package dev.mimir.daemon

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import dev.mimir.runtime.MimirRuntime
import dev.mimir.runtime.update.DisabledUpdateManager
import dev.mimir.runtime.update.UpdateScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Проверка composition root демона: реальный менеджер обновлений и расписание запускаются, только если проверка включена. */
class UpdateWiringTest {
    private val configDir = Files.createTempDirectory("mimir-upd-wiring")
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val runtimes = mutableListOf<MimirRuntime>()
    private val requested = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        runtimes.forEach { it.close() }
        scope.cancel()
        configDir.toFile().deleteRecursively()
    }

    private fun config(updateCheck: Boolean) = DaemonConfig(configDir = configDir.toString(), updateCheck = updateCheck)

    private fun githubClient() = HttpClient(
        MockEngine { request ->
            requested += request.url.toString()
            respond(
                content = """
                    {"tag_name":"v99.0.0","body":"notes","published_at":"2026-11-01T10:00:00Z",
                     "html_url":"https://github.example/releases/v99.0.0",
                     "assets":[{"name":"mimir-99.0.0.msi","browser_download_url":"https://github.example/mimir.msi","size":3}]}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    private fun fastScheduler(started: MutableList<UpdateScheduler>): (UpdateManager, () -> Boolean) -> UpdateScheduler =
        { manager, busy ->
            UpdateScheduler(manager, scope, busy, initialDelay = 10.milliseconds, interval = 50.milliseconds)
                .also(started::add)
        }

    private class RecordingApplier : UpdateApplier {
        override val capability = ApplierCapability.DOWNLOAD_AND_LAUNCH
        val downloads = mutableListOf<UpdateAsset>()

        override suspend fun download(
            asset: UpdateAsset,
            release: UpdateRelease,
            onProgress: (Float, Long, Long) -> Unit,
        ): Result<DownloadedAsset> {
            downloads += asset
            return Result.success(DownloadedAsset.Virtual(asset))
        }

        override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult = UpdateApplyResult.Success("ok", false)
    }

    @Test
    fun `enabled runtime builds a GitHub-backed manager on the daemon http client and starts the scheduler`() = runBlocking {
        val started = mutableListOf<UpdateScheduler>()
        val runtime = createRuntime(config(true), httpClient = githubClient(), updateSchedulerFactory = fastScheduler(started))
            .also { runtimes += it }

        assertNotSame(DisabledUpdateManager, runtime.updateManager)
        runtime.initialize()

        assertEquals(1, started.size)
        withTimeout(10_000) { while (requested.isEmpty()) delay(10) }
        assertTrue(requested.all { "api.github.com/repos/" in it }, "unexpected requests: $requested")
        withTimeout(10_000) { while (runtime.updateManager.state.value !is UpdateState.Available) delay(10) }
    }

    @Test
    fun `disabled runtime keeps DisabledUpdateManager and never creates a scheduler or touches the network`() = runBlocking {
        val started = mutableListOf<UpdateScheduler>()
        val runtime = createRuntime(config(false), httpClient = githubClient(), updateSchedulerFactory = fastScheduler(started))
            .also { runtimes += it }

        assertSame(DisabledUpdateManager, runtime.updateManager)
        runtime.initialize()
        delay(200)

        assertEquals(0, started.size)
        assertEquals(emptyList(), requested)
    }

    @Test
    fun `createDaemonUpdateManager returns null when disabled`() {
        assertNull(createDaemonUpdateManager(config(false), githubClient(), TargetPlatform.DOCKER_ANY, DockerLogNotifyApplier()))
    }

    @Test
    fun `injected applier and target platform reach the manager`() = runBlocking {
        val applier = RecordingApplier()
        val manager = createDaemonUpdateManager(config(true), githubClient(), TargetPlatform.WINDOWS_X64, applier)!!

        assertIs<UpdateCheckResult.UpdateAvailable>(manager.checkForUpdates(force = true))
        val download = manager.downloadUpdate()

        assertTrue(download.isSuccess)
        assertEquals(listOf("mimir-99.0.0.msi"), applier.downloads.map { it.name })
    }
}
