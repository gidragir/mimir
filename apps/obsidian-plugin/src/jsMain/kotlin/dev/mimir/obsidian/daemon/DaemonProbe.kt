package dev.mimir.obsidian.daemon

import dev.mimir.obsidian.platform.RequestUrlFn
import dev.mimir.obsidian.platform.UrlRequest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Статус присутствия локального демона Mimir.
 */
sealed interface DaemonPresence {
    /** Демон не отвечает, не десктоп или выключен. */
    data object Absent : DaemonPresence

    /** Демон обслуживает ровно это же Obsidian-хранилище. */
    data object ServesThisVault : DaemonPresence

    /** Демон обслуживает другое хранилище на этой же машине. */
    data object ServesOtherVault : DaemonPresence

    /** Демон требует авторизацию (MimirTokenAuth 401). */
    data object Unauthorized : DaemonPresence
}

/**
 * Зондирование локального демона Mimir через HTTP API `/api/v1/status`.
 */
class DaemonProbe(
    private val request: RequestUrlFn,
    private val port: Int,
    private val isDesktop: Boolean,
    private val vaultBasePath: String?,
    private val timeout: Duration = 1.seconds,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    private data class StatusDto(val vaultPath: String? = null)

    suspend fun check(): DaemonPresence {
        if (!isDesktop) return DaemonPresence.Absent

        val url = "http://127.0.0.1:$port/api/v1/status"
        val req = UrlRequest(
            url = url,
            method = "GET",
            headers = emptyMap(),
            body = null,
        )

        val response = withTimeoutOrNull(timeout) {
            try {
                request.invoke(req)
            } catch (_: Throwable) {
                null
            }
        } ?: return DaemonPresence.Absent

        return when (response.status) {
            401 -> DaemonPresence.Unauthorized
            200 -> {
                val bodyText = response.body.decodeToString()
                val dto = try {
                    json.decodeFromString<StatusDto>(bodyText)
                } catch (_: Throwable) {
                    return DaemonPresence.Absent
                }

                val daemonVault = dto.vaultPath
                if (daemonVault != null && vaultBasePath != null && normalizePath(daemonVault) == normalizePath(vaultBasePath)) {
                    DaemonPresence.ServesThisVault
                } else {
                    DaemonPresence.ServesOtherVault
                }
            }
            else -> DaemonPresence.Absent
        }
    }

    private fun normalizePath(path: String): String {
        val unified = path.replace('\\', '/').trimEnd('/')
        return if (unified.length >= 2 && unified[1] == ':') {
            unified[0].lowercaseChar() + unified.substring(1)
        } else {
            unified
        }
    }
}
