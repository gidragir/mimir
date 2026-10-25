package dev.mimir.desktop

import dev.mimir.core.SyncProgress
import dev.mimir.core.SyncStatus
import dev.mimir.daemon.BrowserLoginPoll
import dev.mimir.daemon.ConflictFileEntry
import dev.mimir.daemon.DaemonService
import dev.mimir.daemon.DevicePoll
import dev.mimir.daemon.InvalidPasswordException
import dev.mimir.daemon.LoginMethod
import dev.mimir.daemon.SyncNotConfiguredException
import dev.mimir.daemon.SyncProgressDto
import dev.mimir.daemon.VaultInspectionDto
import dev.mimir.daemon.toModel
import dev.mimir.drive.api.GoogleDeviceCodeResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
private data class StatusDto(
    val status: String = "not_connected",
    val label: String = "",
    val vaultPath: String? = null,
    val lastSyncAt: String? = null,
    val isLocked: Boolean = true,
    val accountEmail: String? = null,
    val progress: SyncProgressDto? = null,
)

@Serializable
private data class VaultConfiguredDto(val vaultPath: String = "", val fileCount: Int = 0)

@Serializable
private data class ConflictsDto(val conflicts: List<ConflictDto> = emptyList())

@Serializable
private data class ConflictDto(val originalPath: String, val conflictPath: String, val createdAt: String)

@Serializable
private data class PollDto(val status: String = "", val email: String? = null, val error: String? = null)

@Serializable
private data class MethodsDto(val methods: List<String> = emptyList())

@Serializable
private data class BrowserStartDto(val authUrl: String)

@Serializable
private data class ErrorDto(val error: String? = null)

@Serializable
private data class ResolveDto(val originalPath: String, val conflictPath: String, val resolution: String)

@Serializable
private data class UnlockDto(val password: String)

@Serializable
private data class VaultDto(val vaultPath: String)

fun defaultDaemonHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** [DaemonService] поверх HTTP API уже запущенного демона: состояние опрашивается раз в [pollInterval]. */
class HttpDaemonClient(
    private val baseUrl: String,
    private val token: String? = null,
    scope: CoroutineScope,
    private val httpClient: HttpClient = defaultDaemonHttpClient(),
    pollInterval: Duration = 1.seconds,
) : DaemonService {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    private val _conflicts = MutableStateFlow<List<ConflictFileEntry>>(emptyList())

    @Volatile
    private var snapshot = StatusDto()

    override val status: StateFlow<SyncStatus> = _status
    override val conflictsFlow: StateFlow<List<ConflictFileEntry>> = _conflicts

    override val progress: StateFlow<SyncProgress?> = _progress
    override val vaultPath: String? get() = snapshot.vaultPath
    override val isLocked: Boolean get() = snapshot.isLocked
    override val lastSyncTime: Instant? get() = snapshot.lastSyncAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    override val accountEmail: String? get() = snapshot.accountEmail

    override suspend fun loginMethods(): Set<LoginMethod> {
        val response = get("/api/v1/auth/methods")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return response.body<MethodsDto>().methods.mapNotNull {
            when (it) {
                "browser" -> LoginMethod.Browser
                "device_code" -> LoginMethod.DeviceCode
                else -> null
            }
        }.toSet()
    }

    override suspend fun startBrowserLogin(): String {
        val response = post("/api/v1/auth/browser/start")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return response.body<BrowserStartDto>().authUrl
    }

    override suspend fun pollBrowserLogin(): BrowserLoginPoll {
        val response = get("/api/v1/auth/browser/poll")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        val body = response.body<PollDto>()
        return when (body.status) {
            "pending" -> BrowserLoginPoll.Pending
            "authorized" -> BrowserLoginPoll.Authorized(body.email)
            "failed" -> BrowserLoginPoll.Failed(body.error ?: "Вход не удался")
            else -> throw IllegalStateException("Unexpected sign-in status: ${body.status}")
        }
    }

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(pollInterval)
            }
        }
    }

    private suspend fun refresh() {
        try {
            val dto = get("/api/v1/status").body<StatusDto>()
            snapshot = dto
            _progress.value = dto.progress?.let { runCatching { it.toModel() }.getOrNull() }
            _status.value = when (dto.status) {
                "idle" -> SyncStatus.Idle
                "syncing" -> SyncStatus.Syncing
                "offline" -> SyncStatus.Offline
                "error" -> SyncStatus.Error(dto.label.removePrefix("Mimir: ").ifEmpty { "error" })
                else -> SyncStatus.NotConfigured
            }
            _conflicts.value = fetchConflicts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // демон недоступен: показываем последнее известное состояние
        }
    }

    private suspend fun fetchConflicts(): List<ConflictFileEntry> =
        get("/api/v1/conflicts").body<ConflictsDto>().conflicts.map {
            ConflictFileEntry(it.originalPath, it.conflictPath, Instant.parse(it.createdAt))
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
        val response = post("/api/v1/unlock", UnlockDto(password))
        if (response.status != HttpStatusCode.OK) throw response.toException()
    }

    override suspend fun conflicts(): List<ConflictFileEntry> = fetchConflicts().also { _conflicts.value = it }

    override suspend fun resolveConflict(originalPath: String, conflictPath: String, keepConflict: Boolean) {
        val resolution = if (keepConflict) "keep_conflict" else "keep_original"
        val response = post("/api/v1/conflicts/resolve", ResolveDto(originalPath, conflictPath, resolution))
        if (response.status != HttpStatusCode.OK) throw response.toException()
        conflicts()
    }

    override suspend fun startDeviceLogin(): GoogleDeviceCodeResponse {
        val response = post("/api/v1/auth/device/start")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return response.body()
    }

    override suspend fun pollDeviceLogin(deviceCode: String): DevicePoll {
        val response = httpClient.get("$baseUrl/api/v1/auth/device/poll") {
            authorize()
            parameter("device_code", deviceCode)
        }
        val body = runCatching { response.body<PollDto>() }.getOrDefault(PollDto())
        return when {
            body.status == "pending" -> DevicePoll.Pending
            body.status == "authorized" -> DevicePoll.Authorized(body.email)
            body.status == "expired" -> DevicePoll.Expired(body.error ?: "expired")
            else -> throw response.toException()
        }
    }

    override suspend fun configureVault(vaultPath: String): Int {
        val response = post("/api/v1/vault", VaultDto(vaultPath))
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return response.body<VaultConfiguredDto>().fileCount
    }

    override suspend fun inspectVault(): VaultInspectionDto {
        val response = get("/api/v1/vault/inspect")
        if (response.status != HttpStatusCode.OK) throw response.toException()
        return response.body()
    }

    private suspend fun get(path: String): HttpResponse =
        httpClient.get("$baseUrl$path") { authorize() }

    private suspend inline fun <reified T : Any> post(path: String, body: T): HttpResponse =
        httpClient.post("$baseUrl$path") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun post(path: String): HttpResponse = httpClient.post("$baseUrl$path") { authorize() }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpResponse.toException(): Exception {
        val message = runCatching { body<ErrorDto>().error }.getOrNull() ?: "Daemon replied $status"
        return when (status) {
            HttpStatusCode.Forbidden -> InvalidPasswordException(message)
            HttpStatusCode.BadRequest -> IllegalArgumentException(message)
            HttpStatusCode.NotFound -> NoSuchElementException(message)
            HttpStatusCode.PreconditionFailed -> SyncNotConfiguredException(message)
            else -> IllegalStateException(message)
        }
    }

    fun close() = httpClient.close()
}
