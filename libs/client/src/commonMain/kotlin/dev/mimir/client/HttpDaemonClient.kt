package dev.mimir.client

import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
private data class PollResponseDto(
    val status: String = "",
    val email: String? = null,
    val error: String? = null,
)

/** [DaemonService] поверх HTTP API демона с SSE-подпиской и fallback-опросом. */
class HttpDaemonClient(
    baseUrl: String,
    token: String? = null,
    scope: CoroutineScope,
    httpClient: HttpClient = defaultDaemonHttpClient(),
    pollInterval: Duration = 1.seconds,
) : DaemonService {
    private val json = Json { ignoreUnknownKeys = true }
    private val transport = DaemonHttpTransport(baseUrl, token, httpClient, json)

    private val _connectionStatus = MutableStateFlow(DaemonConnectionStatus.Connecting)
    override val connectionStatus: StateFlow<DaemonConnectionStatus> = _connectionStatus

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    override val status: StateFlow<SyncStatus> = _status

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    override val progress: StateFlow<SyncProgress?> = _progress

    private val _conflicts = MutableStateFlow<List<ConflictFileEntry>>(emptyList())
    override val conflictsFlow: StateFlow<List<ConflictFileEntry>> = _conflicts

    @Volatile
    private var snapshot = DaemonStatusDto(
        status = "not_connected",
        label = "",
        vaultPath = null,
        lastSyncAt = null,
        isLocked = true,
        activeConflicts = 0,
        accountEmail = null,
    )

    @Volatile
    private var conflictsLoaded = false

    @Volatile
    override var lastSyncTime: Instant? = null
        private set

    override val vaultPath: String? get() = snapshot.vaultPath
    override val isLocked: Boolean get() = snapshot.isLocked
    override val accountEmail: String? get() = snapshot.accountEmail

    init {
        scope.launch { subscribeUpdates(pollInterval) }
    }

    private suspend fun subscribeUpdates(pollInterval: Duration) {
        while (true) {
            val outcome = transport.streamSse("/api/v1/events") { payload ->
                runCatching { json.decodeFromString<DaemonStatusDto>(payload) }.getOrNull()?.let { applyStatus(it) }
            }
            when (outcome) {
                SseStreamOutcome.Unsupported -> break
                SseStreamOutcome.Failed -> {
                    markUnreachable()
                    delay(pollInterval)
                }
                SseStreamOutcome.Ended -> delay(pollInterval)
            }
        }
        while (true) {
            refresh()
            delay(pollInterval)
        }
    }

    suspend fun refresh() {
        try {
            val response = transport.get("/api/v1/status")
            if (response.status != HttpStatusCode.OK) {
                markUnreachable()
                return
            }
            applyStatus(json.decodeFromString(response.bodyAsText()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markUnreachable()
        }
    }

    private suspend fun applyStatus(dto: DaemonStatusDto) {
        val shouldFetchConflicts = dto.activeConflicts > 0 && (
            !conflictsLoaded ||
                dto.activeConflicts != snapshot.activeConflicts ||
                dto.lastSyncAt != snapshot.lastSyncAt
            )
        snapshot = dto
        lastSyncTime = dto.lastSyncAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        _progress.value = dto.progress?.let { runCatching { it.toModel() }.getOrNull() }
        _status.value = when (dto.status) {
            "idle" -> SyncStatus.Idle
            "syncing" -> SyncStatus.Syncing
            "offline" -> SyncStatus.Offline
            "error" -> SyncStatus.Error(dto.label.removePrefix("Mimir: ").ifEmpty { "error" })
            else -> SyncStatus.NotConfigured
        }
        _connectionStatus.value = DaemonConnectionStatus.Connected
        if (dto.activeConflicts == 0) {
            conflictsLoaded = false
            _conflicts.value = emptyList()
        } else if (shouldFetchConflicts) {
            runCatching { fetchConflicts() }.getOrNull()?.let {
                _conflicts.value = it
                conflictsLoaded = true
            }
        }
    }

    /** Без связи с демоном последний Idle/Syncing устарел: интерфейс показывает «нет связи». */
    private fun markUnreachable() {
        _connectionStatus.value = DaemonConnectionStatus.Reconnecting
        _status.value = SyncStatus.Offline
        _progress.value = null
    }

    private suspend fun fetchConflicts(): List<ConflictFileEntry> =
        transport.getJson<DaemonConflictsResponse>("/api/v1/conflicts").conflicts.map {
            ConflictFileEntry(it.originalPath, it.conflictPath, Instant.parse(it.createdAt))
        }

    override suspend fun triggerSync(): Boolean {
        val response = transport.post("/api/v1/sync")
        return when (response.status) {
            HttpStatusCode.Accepted -> true
            HttpStatusCode.Conflict -> false
            else -> throw with(transport) { response.toException() }
        }
    }

    override suspend fun unlock(password: String) =
        transport.postExpect("/api/v1/unlock", DaemonUnlockRequest(password))

    override suspend fun conflicts(): List<ConflictFileEntry> =
        fetchConflicts().also {
            _conflicts.value = it
            conflictsLoaded = true
        }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        val resolution = if (keepConflict) "keep_conflict" else "keep_original"
        transport.postExpect("/api/v1/conflicts/resolve", DaemonResolveConflictRequest(originalPath, conflictPath, resolution))
        conflicts()
    }

    override suspend fun configureVault(vaultPath: String): Int =
        transport.postJson<DaemonVaultRequest, DaemonVaultConfigResponse>("/api/v1/vault", DaemonVaultRequest(vaultPath)).fileCount

    override suspend fun inspectVault(): DaemonVaultInspectionDto = transport.getJson("/api/v1/vault/inspect")
    override suspend fun getUpdate(): UpdateStatusDto = transport.getJson("/api/v1/update")
    override suspend fun checkUpdates(): UpdateStatusDto = transport.postJson("/api/v1/update/check")
    override suspend fun downloadUpdate() = transport.postExpect("/api/v1/update/download", HttpStatusCode.Accepted)
    override suspend fun applyUpdate(): UpdateApplyDto = transport.postJson("/api/v1/update/apply")

    override suspend fun loginMethods(): Set<DaemonLoginMethod> {
        val body = transport.getJson<DaemonMethodsResponse>("/api/v1/auth/methods")
        // Системный аккаунт через HTTP API не начать: он не предлагается удалённому клиенту.
        return body.methods.mapNotNull(::daemonLoginMethodOf).filterTo(mutableSetOf()) { it != DaemonLoginMethod.SystemAccount }
    }

    private class ActiveLogin(val method: DaemonLoginMethod, val deviceCode: String? = null)

    @Volatile
    private var activeLogin: ActiveLogin? = null

    override suspend fun startLogin(method: DaemonLoginMethod): DaemonLoginStart = when (method) {
        DaemonLoginMethod.SystemAccount ->
            throw UnsupportedOperationException("System account sign-in is not available through the daemon API")
        DaemonLoginMethod.Browser -> {
            val url = transport.postJson<DaemonAuthUrlResponse>("/api/v1/auth/browser/start").authUrl
            activeLogin = ActiveLogin(method)
            DaemonLoginStart.OpenUrl(url)
        }
        DaemonLoginMethod.DeviceCode -> {
            val code = transport.postJson<DaemonDeviceCodeDto>("/api/v1/auth/device/start")
            activeLogin = ActiveLogin(method, code.deviceCode)
            DaemonLoginStart.ShowDeviceCode(code)
        }
    }

    override suspend fun pollLogin(): DaemonLoginPoll {
        val active = activeLogin ?: return DaemonLoginPoll.Failed("Вход не начат")
        return when (active.method) {
            DaemonLoginMethod.DeviceCode -> pollDeviceLogin(active.deviceCode.orEmpty())
            else -> pollBrowserLogin()
        }
    }

    private suspend fun pollBrowserLogin(): DaemonLoginPoll {
        val body = transport.getJson<PollResponseDto>("/api/v1/auth/browser/poll")
        return when (body.status) {
            "pending" -> DaemonLoginPoll.Pending
            "authorized" -> DaemonLoginPoll.Authorized(body.email)
            "failed" -> DaemonLoginPoll.Failed(body.error ?: "Вход не удался")
            else -> throw IllegalStateException("Unexpected sign-in status: ${body.status}")
        }
    }

    private suspend fun pollDeviceLogin(deviceCode: String): DaemonLoginPoll {
        val response = transport.get("/api/v1/auth/device/poll") { parameter("device_code", deviceCode) }
        val body = runCatching { json.decodeFromString<PollResponseDto>(response.bodyAsText()) }.getOrDefault(PollResponseDto())
        return when (body.status) {
            "pending" -> DaemonLoginPoll.Pending
            "authorized" -> DaemonLoginPoll.Authorized(body.email)
            "expired" -> DaemonLoginPoll.Failed(body.error ?: "expired")
            else -> throw with(transport) { response.toException() }
        }
    }

    fun close() {
        _connectionStatus.value = DaemonConnectionStatus.Disconnected
        transport.close()
    }
}
