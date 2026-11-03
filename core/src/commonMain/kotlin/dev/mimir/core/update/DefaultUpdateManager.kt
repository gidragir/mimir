package dev.mimir.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Конечный автомат обновления. Mutex защищает только переходы состояния и проверку канала;
 * долгие скачивание и установка идут вне замка, поэтому параллельный [checkForUpdates]
 * не блокируется и не затирает состояние идущего прогона.
 */
class DefaultUpdateManager(
    private val currentVersion: SemVer,
    private val targetPlatform: TargetPlatform,
    private val channel: UpdateChannel,
    private val applier: UpdateApplier,
    private val clock: Clock = Clock.System,
) : UpdateManager {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle())
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheckedAt: Instant? = null
    private var latestKnownVersion: SemVer? = null

    override suspend fun checkForUpdates(force: Boolean): UpdateCheckResult {
        busyResult()?.let { return it }
        return mutex.withLock {
            busyResult()?.let { return@withLock it }
            val previous = _state.value
            _state.value = UpdateState.Checking
            val result = try {
                channel.checkForUpdates(currentVersion, targetPlatform)
            } catch (e: CancellationException) {
                _state.value = previous
                throw e
            } catch (e: Throwable) {
                UpdateCheckResult.Error(e.message ?: "Update check failed", e)
            }
            when (result) {
                is UpdateCheckResult.NoUpdate -> {
                    lastCheckedAt = clock.now()
                    latestKnownVersion = result.checkedVersion
                    _state.value = idle()
                }
                is UpdateCheckResult.UpdateAvailable -> {
                    lastCheckedAt = clock.now()
                    latestKnownVersion = result.release.version
                    _state.value = UpdateState.Available(result.release, result.matchedAsset, applier.capability)
                }
                is UpdateCheckResult.Error -> _state.value = UpdateState.Error(result.message, previous)
            }
            result
        }
    }

    /** Ответ на проверку во время активного прогона (состояние не меняется); `null` — прогона нет. */
    private fun busyResult(): UpdateCheckResult? = when (val s = _state.value) {
        is UpdateState.Downloading -> UpdateCheckResult.UpdateAvailable(s.release, s.asset)
        is UpdateState.Installing -> UpdateCheckResult.UpdateAvailable(s.release, null)
        is UpdateState.ReadyToInstall -> UpdateCheckResult.UpdateAvailable(s.release, s.downloadedAsset.asset)
        else -> null
    }

    override suspend fun downloadUpdate(): Result<DownloadedAsset> {
        val available = mutex.withLock {
            val s = _state.value.let { if (it is UpdateState.Error) it.previousState else it }
            if (s !is UpdateState.Available) {
                return Result.failure(IllegalStateException("Cannot download in state ${s::class.simpleName}"))
            }
            val asset = s.asset
                ?: return Result.failure(IllegalStateException("No installable asset for ${targetPlatform.name}"))
            _state.value = UpdateState.Downloading(s.release, asset, 0f, 0L, asset.sizeBytes)
            s
        }
        val asset = checkNotNull(available.asset)
        val result = try {
            applier.download(asset, available.release) { progress, read, total ->
                _state.update {
                    if (it is UpdateState.Downloading) it.copy(progress = progress, bytesRead = read, totalBytes = total) else it
                }
            }
        } catch (e: CancellationException) {
            _state.value = available
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        val error = result.exceptionOrNull()
        if (error != null) {
            if (error is CancellationException) {
                _state.value = available
                throw error
            }
            _state.value = UpdateState.Error(error.message ?: "Download failed", available)
            return result
        }
        _state.value = UpdateState.ReadyToInstall(available.release, result.getOrThrow(), available.capability)
        return result
    }

    override suspend fun applyUpdate(): UpdateApplyResult {
        val ready = mutex.withLock {
            val s = _state.value.let { if (it is UpdateState.Error) it.previousState else it }
            if (s !is UpdateState.ReadyToInstall) {
                return UpdateApplyResult.Failed("Cannot apply in state ${s::class.simpleName}")
            }
            applyInFlight = true
            _state.value = UpdateState.Installing(s.release)
            s
        }
        val result = try {
            applier.apply(ready.downloadedAsset)
        } catch (e: CancellationException) {
            applyInFlight = false
            _state.value = ready
            throw e
        } catch (e: Throwable) {
            UpdateApplyResult.Failed(e.message ?: "Update failed", e)
        }
        applyInFlight = false
        _state.value = when (result) {
            is UpdateApplyResult.Failed -> UpdateState.Error(result.error, ready)
            is UpdateApplyResult.LaunchedInstaller -> UpdateState.Installing(ready.release)
            is UpdateApplyResult.Success ->
                if (result.requiresRestart) UpdateState.Installing(ready.release) else idle()
            is UpdateApplyResult.NotificationDispatched -> idle()
        }
        return result
    }

    /**
     * Сброс в Idle. No-op, пока идут проверка, скачивание или сам вызов apply.
     * После возврата apply (установщик запущен, состояние Installing) dismiss возвращает Idle,
     * чтобы состояние не залипало навсегда.
     */
    override fun dismiss() {
        _state.update { s ->
            when {
                s is UpdateState.Downloading || s === UpdateState.Checking -> s
                s is UpdateState.Installing && applyInFlight -> s
                else -> idle()
            }
        }
    }

    @kotlin.concurrent.Volatile
    private var applyInFlight = false

    private fun idle() = UpdateState.Idle(lastCheckedAt, latestKnownVersion)
}
