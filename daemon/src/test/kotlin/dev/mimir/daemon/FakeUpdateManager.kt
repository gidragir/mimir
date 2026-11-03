package dev.mimir.daemon

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.AssetKind
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.SemVer
import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateCheckResult
import dev.mimir.core.update.UpdateManager
import dev.mimir.core.update.UpdateRelease
import dev.mimir.core.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

val testAsset = UpdateAsset("mimir.msi", "https://example.test/mimir.msi", 10, "abc", TargetPlatform.WINDOWS_X64, AssetKind.MSI)

val testRelease = UpdateRelease(
    version = SemVer.parse("1.2.3"),
    releaseNotes = "Fixes",
    publishedAt = Instant.parse("2026-11-01T10:00:00Z"),
    htmlUrl = "https://example.test/releases/v1.2.3",
    assets = listOf(testAsset),
)

/** Фейк менеджера: состояние задаётся тестом, вызовы считаются, переходы повторяют реальный менеджер в минимуме. */
class FakeUpdateManager(initial: UpdateState = UpdateState.Idle()) : UpdateManager {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<UpdateState> = _state

    var checkResult: UpdateCheckResult = UpdateCheckResult.NoUpdate(SemVer.parse("0.1.0"))
    var checkForced: Boolean? = null
    var checkCalls = 0
    var downloadCalls = 0
    var applyCalls = 0
    var downloadGate: CompletableDeferred<Unit>? = null
    var downloadResult: Result<DownloadedAsset> =
        Result.success(DownloadedAsset.LocalFile("/tmp/secret-dir/mimir.msi", "abc", testAsset))
    var applyResult: UpdateApplyResult = UpdateApplyResult.LaunchedInstaller("Installer started")

    fun set(state: UpdateState) {
        _state.value = state
    }

    fun available(capability: ApplierCapability = ApplierCapability.DOWNLOAD_AND_LAUNCH) =
        UpdateState.Available(testRelease, testAsset, capability)

    override suspend fun checkForUpdates(force: Boolean): UpdateCheckResult {
        checkCalls++
        checkForced = force
        return checkResult
    }

    override suspend fun downloadUpdate(): Result<DownloadedAsset> {
        downloadCalls++
        val before = _state.value as? UpdateState.Available
        if (before != null) _state.value = UpdateState.Downloading(testRelease, testAsset, 0f, 0, 10)
        downloadGate?.await()
        downloadResult.onFailure { _state.value = UpdateState.Error(it.message.orEmpty(), before ?: UpdateState.Idle()) }
            .onSuccess { _state.value = UpdateState.ReadyToInstall(testRelease, it, ApplierCapability.DOWNLOAD_AND_LAUNCH) }
        return downloadResult
    }

    override suspend fun applyUpdate(): UpdateApplyResult {
        applyCalls++
        return applyResult
    }

    override fun dismiss() = Unit
}
