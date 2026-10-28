package dev.mimir.client

import dev.mimir.core.ConflictFileEntry
import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
private data class PollResponseDto(
    val status: String = "",
    val email: String? = null,
    val error: String? = null,
)

/** Клиент с таймаутами: зависший демон не должен вешать опрос и кнопки интерфейса. */
internal fun HttpClientConfig<*>.installDaemonTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = 1_000
        requestTimeoutMillis = 5_000
    }
}

private fun defaultDaemonHttpClient(): HttpClient = HttpClient { installDaemonTimeouts() }

/** [DaemonService] поверх HTTP API демона с отслеживанием жизненного цикла соединения. */
class HttpDaemonClient(
    private val baseUrl: String,
    private val token: String? = null,
    scope: CoroutineScope,
    private val httpClient: HttpClient = defaultDaemonHttpClient(),
    pollInterval: Duration = 1.seconds,
) : DaemonService {
    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionStatus = MutableStateFlow(DaemonConnectionStatus.Connecting)
    override val connectionStatus: StateFlow<DaemonConnectionStatus> = _connectionStatus

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    override val status: StateFlow<SyncStatus> = _status

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    override val progress: StateFlow<SyncProgress?> = _progress

    private val _conflicts = MutableStateFlow<List<ConflictFileEntry>>(emptyList())
    override val conflictsFlow: StateFlow<List<ConflictFileEntry>> = _conflicts

    @kotlin.concurrent.Volatile
    private var snapshot = DaemonStatusDto(
        status = "not_configured",
        label = "",
        vaultPath = null,
        lastSyncAt = null,
        isLocked = true,
        activeConflicts = 0,
        accountEmail = null,
    )

    override val vaultPath: String? get() = snapshot.vaultPath
    override val isLocked: Boolean get() = snapshot.isLocked
    override val lastSyncTime: Instant? get() = snapshot.lastSyncAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    override val accountEmail: String? get() = snapshot.accountEmail

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(pollInterval)
            }
        }
    }

    suspend fun refresh() {
        try {
            val response = get("/api/v1/status")
            if (response.status != HttpStatusCode.OK) {
                markUnreachable()
                return
            }
            val dto = json.decodeFromString<DaemonStatusDto>(response.bodyAsText())
            snapshot = dto
            _progress.value = dto.progress?.let { runCatching { it.toModel() }.getOrNull() }
            _status.value = when (dto.status) {
                "idle" -> SyncStatus.Idle
                "syncing" -> SyncStatus.Syncing
                "offline" -> SyncStatus.Offline
                "error" -> SyncStatus.Error(dto.label.removePrefix("Mimir: ").ifEmpty { "error" })
                else -> SyncStatus.NotConfigured
            }
            _connectionStatus.value = DaemonConnectionStatus.Connected
            if (dto.activeConflicts > 0) {
                runCatching { fetchConflicts() }.getOrNull()?.let { _conflicts.value = it }
            } else {
                _conflicts.value = emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markUnreachable()
        }
    }

    /** Без связи с демоном последний Idle/Syncing устарел: интерфейс показывает «нет связи». */
    private fun markUnreachable() {
        _connectionStatus.value = DaemonConnectionStatus.Reconnecting
        _status.value = SyncStatus.Offline
        _progress.value = null
    }

    private suspend fun fetchConflicts(): List<ConflictFileEntry> {
        val response = get("/api/v1/conflicts")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        val body = json.decodeFromString<DaemonConflictsResponse>(response.bodyAsText())
        return body.conflicts.map {
            ConflictFileEntry(it.originalPath, it.conflictPath, Instant.parse(it.createdAt))
        }
    }

    override suspend fun triggerSync(): Boolean {
        val response = post("/api/v1/sync")
        return when (response.status) {
            HttpStatusCode.Accepted -> true
            HttpStatusCode.Conflict -> false
            else -> throw response.toException()
        }
    }

    override suspend fun unlock(password: String) {
        val response = post("/api/v1/unlock", DaemonUnlockRequest(password))
        if (response.status != HttpStatusCode.OK) throw response.toException()
    }

    override suspend fun conflicts(): List<ConflictFileEntry> =
        fetchConflicts().also { _conflicts.value = it }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        val resolution = if (keepConflict) "keep_conflict" else "keep_original"
        val response = post(
            "/api/v1/conflicts/resolve",
            DaemonResolveConflictRequest(originalPath, conflictPath, resolution),
        )
        if (response.status != HttpStatusCode.OK) throw response.toException()
        conflicts()
    }

    override suspend fun configureVault(vaultPath: String): Int {
        val response = post("/api/v1/vault", DaemonVaultRequest(vaultPath))
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return json.decodeFromString<DaemonVaultConfigResponse>(response.bodyAsText()).fileCount
    }

    override suspend fun inspectVault(): DaemonVaultInspectionDto {
        val response = get("/api/v1/vault/inspect")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return json.decodeFromString<DaemonVaultInspectionDto>(response.bodyAsText())
    }

    override suspend fun loginMethods(): Set<DaemonLoginMethod> {
        val response = get("/api/v1/auth/methods")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        val body = json.decodeFromString<DaemonMethodsResponse>(response.bodyAsText())
        // Системный аккаунт через HTTP API не начать: он не предлагается удалённому клиенту.
        return body.methods.mapNotNull(::daemonLoginMethodOf).filterTo(mutableSetOf()) { it != DaemonLoginMethod.SystemAccount }
    }

    /** Последний начатый способ входа и код устройства для опроса (маршруты демона принимают его в запросе). */
    private class ActiveLogin(val method: DaemonLoginMethod, val deviceCode: String? = null)

    @kotlin.concurrent.Volatile
    private var activeLogin: ActiveLogin? = null

    override suspend fun startLogin(method: DaemonLoginMethod): DaemonLoginStart = when (method) {
        DaemonLoginMethod.SystemAccount ->
            throw UnsupportedOperationException("System account sign-in is not available through the daemon API")
        DaemonLoginMethod.Browser -> {
            val response = post("/api/v1/auth/browser/start")
            if (response.status != HttpStatusCode.OK) throw response.toException()
            val url = json.decodeFromString<DaemonAuthUrlResponse>(response.bodyAsText()).authUrl
            activeLogin = ActiveLogin(method)
            DaemonLoginStart.OpenUrl(url)
        }
        DaemonLoginMethod.DeviceCode -> {
            val code = startDeviceLogin()
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
        val response = get("/api/v1/auth/browser/poll")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        val body = json.decodeFromString<PollResponseDto>(response.bodyAsText())
        return when (body.status) {
            "pending" -> DaemonLoginPoll.Pending
            "authorized" -> DaemonLoginPoll.Authorized(body.email)
            "failed" -> DaemonLoginPoll.Failed(body.error ?: "Вход не удался")
            else -> throw IllegalStateException("Unexpected sign-in status: ${body.status}")
        }
    }

    private suspend fun startDeviceLogin(): DaemonDeviceCodeDto {
        val response = post("/api/v1/auth/device/start")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        val text = response.bodyAsText()
        val elem = json.parseToJsonElement(text).jsonObject
        return DaemonDeviceCodeDto(
            deviceCode = elem["device_code"]?.jsonPrimitive?.content
                ?: elem["deviceCode"]?.jsonPrimitive?.content ?: "",
            userCode = elem["user_code"]?.jsonPrimitive?.content
                ?: elem["userCode"]?.jsonPrimitive?.content ?: "",
            verificationUrl = elem["verification_url"]?.jsonPrimitive?.content
                ?: elem["verificationUrl"]?.jsonPrimitive?.content ?: "",
            expiresInSeconds = elem["expires_in"]?.jsonPrimitive?.longOrNull
                ?: elem["expiresInSeconds"]?.jsonPrimitive?.longOrNull ?: 1800L,
            intervalSeconds = elem["interval"]?.jsonPrimitive?.longOrNull
                ?: elem["intervalSeconds"]?.jsonPrimitive?.longOrNull ?: 5L,
        )
    }

    private suspend fun pollDeviceLogin(deviceCode: String): DaemonLoginPoll {
        val response = httpClient.get("$baseUrl/api/v1/auth/device/poll") {
            authorize()
            parameter("device_code", deviceCode)
        }
        val text = response.bodyAsText()
        val body = runCatching {
            json.decodeFromString<PollResponseDto>(text)
        }.getOrDefault(PollResponseDto())
        return when (body.status) {
            "pending" -> DaemonLoginPoll.Pending
            "authorized" -> DaemonLoginPoll.Authorized(body.email)
            "expired" -> DaemonLoginPoll.Failed(body.error ?: "expired")
            else -> throw response.toException()
        }
    }

    private suspend fun get(path: String): HttpResponse =
        httpClient.get("$baseUrl$path") { authorize() }

    private suspend inline fun <reified T : Any> post(path: String, body: T): HttpResponse =
        httpClient.post("$baseUrl$path") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }

    private suspend fun post(path: String): HttpResponse =
        httpClient.post("$baseUrl$path") { authorize() }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpResponse.toException(): Exception {
        val message = runCatching {
            json.decodeFromString<DaemonErrorBody>(bodyAsText()).error
        }.getOrNull() ?: "Daemon replied $status"
        return when (status) {
            HttpStatusCode.Forbidden -> InvalidPasswordException(message)
            HttpStatusCode.BadRequest -> IllegalArgumentException(message)
            HttpStatusCode.NotFound -> NoSuchElementException(message)
            HttpStatusCode.PreconditionFailed -> SyncNotConfiguredException(message)
            else -> IllegalStateException(message)
        }
    }

    fun close() {
        _connectionStatus.value = DaemonConnectionStatus.Disconnected
        httpClient.close()
    }
}
