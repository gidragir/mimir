package dev.mimir.runtime.update

import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpResponse
import kotlinx.coroutines.CompletableDeferred

/** Одна попытка скачивания: отданные чанки и итог (успех, сетевой сбой, HTTP-статус). */
class ScriptedAttempt(val chunks: List<ByteArray>, val outcome: Result<Unit> = Result.success(Unit))

/**
 * Скриптуемый HTTP-клиент: отдаёт чанки, затем успех/ошибку/ожидание.
 * [attempts] задаёт попытки по порядку (последняя повторяется); без него каждая попытка — [chunks] + [outcome].
 */
class ScriptedUpdateHttpClient(
    private val chunks: List<ByteArray> = emptyList(),
    private val outcome: Result<Unit> = Result.success(Unit),
    private val hangAfterChunks: CompletableDeferred<Unit>? = null,
    private val onFirstChunkSent: CompletableDeferred<Unit>? = null,
    private val attempts: List<ScriptedAttempt> = emptyList(),
) : UpdateHttpClient {
    var downloads = 0

    /** Сколько байт тела отдано по всем попыткам — стоимость повторов. */
    var bytesSent = 0L

    override suspend fun get(url: String, headers: Map<String, String>) = UpdateHttpResponse(200, emptyMap(), "")

    override suspend fun downloadStream(
        url: String,
        headers: Map<String, String>,
        onChunk: suspend (ByteArray, Long, Long) -> Unit,
    ): Result<Unit> {
        val attempt = attempts.getOrElse(downloads) { attempts.lastOrNull() } ?: ScriptedAttempt(chunks, outcome)
        downloads++
        val total = attempt.chunks.sumOf { it.size }.toLong()
        var read = 0L
        for (c in attempt.chunks) {
            read += c.size
            bytesSent += c.size
            onChunk(c, read, total)
            onFirstChunkSent?.complete(Unit)
        }
        hangAfterChunks?.await()
        return attempt.outcome
    }
}
