package dev.mimir.obsidian.update

import dev.mimir.core.BuildConfig
import dev.mimir.core.update.DailyUpdateChecker
import dev.mimir.core.update.ConsoleUpdateLogger
import dev.mimir.core.update.DefaultUpdateManager
import dev.mimir.core.update.FetchUpdateHttpClient
import dev.mimir.core.update.GitHubReleasesChannel
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateState
import dev.mimir.obsidian.Notice
import dev.mimir.obsidian.Plugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.js.json
import kotlin.time.Clock

/**
 * Автономное обновление плагина (без демона): раз в сутки после запуска проверяет GitHub Releases,
 * показывает `Notice`, а команда «Update plugin» скачивает и заменяет `main.js`/`manifest.json` на месте.
 * Ошибки не выходят в Obsidian: они превращаются в уведомления.
 */
class PluginUpdater(private val plugin: Plugin, private val scope: CoroutineScope) {
    private val currentVersion = runCatching { SemVer.parse(plugin.manifest.version) }.getOrElse { SemVer.parse(BuildConfig.VERSION) }
    private val pluginDir = plugin.manifest.dir ?: "${plugin.app.vault.configDir}/plugins/${plugin.manifest.id}"
    private val http = FetchUpdateHttpClient()
    private val manager = DefaultUpdateManager(
        currentVersion = currentVersion,
        targetPlatform = TargetPlatform.OBSIDIAN_ANY,
        channel = GitHubReleasesChannel(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO, http, ConsoleUpdateLogger),
        applier = ObsidianInPlaceApplier(http, pluginDir, plugin.manifest.id, plugin.app.vault.adapter),
        logger = ConsoleUpdateLogger,
    )
    private val daily = DailyUpdateChecker(
        manager,
        Clock.System,
        loadLastChecked = { (plugin.loadData().await()?.updateLastCheckedAt as? Number)?.toLong() },
        saveLastChecked = { value ->
            val data: dynamic = plugin.loadData().await() ?: json()
            data.updateLastCheckedAt = value.toDouble()
            plugin.saveData(data).await()
        },
    )
    private var busy = false

    fun start() {
        plugin.addCommand(
            json(
                "id" to "update-plugin",
                "name" to "Update plugin",
                "callback" to { scope.launch { guarded { updateNow() } } },
            ),
        )
        scope.launch {
            delay(START_DELAY_MS)
            guarded { autoCheck() }
        }
    }

    private suspend fun autoCheck() {
        val result = daily.checkIfDue()
        if (result is UpdateCheckResult.UpdateAvailable) {
            notice("Mimir: version ${result.release.version} is available. Run \"Mimir: Update plugin\" from the command palette.")
        }
    }

    private suspend fun updateNow() {
        if (busy) return
        busy = true
        try {
            if (manager.state.value !is UpdateState.Available) {
                when (val result = manager.checkForUpdates()) {
                    is UpdateCheckResult.NoUpdate -> return notice("Mimir is up to date (${result.checkedVersion}).")
                    is UpdateCheckResult.Error -> return notice("Mimir: update check failed. ${result.message}")
                    is UpdateCheckResult.UpdateAvailable -> Unit
                }
            }
            notice("Mimir: downloading the update...")
            val downloaded = manager.downloadUpdate()
            if (downloaded.isFailure) return notice("Mimir: update failed. ${downloaded.exceptionOrNull()?.message.orEmpty()}")
            when (val applied = manager.applyUpdate()) {
                is UpdateApplyResult.Success -> notice(applied.message)
                is UpdateApplyResult.Failed -> notice("Mimir: update failed. ${applied.error}")
                else -> notice("Mimir: update finished.")
            }
        } finally {
            busy = false
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { notice("Mimir: update failed. ${e.message.orEmpty()}") }
        }
    }

    private fun notice(message: String) {
        Notice(message, NOTICE_MS)
    }

    private companion object {
        const val START_DELAY_MS = 5_000L
        const val NOTICE_MS = 10_000
    }
}
