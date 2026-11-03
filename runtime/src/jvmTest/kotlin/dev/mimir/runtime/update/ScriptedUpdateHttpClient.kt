package dev.mimir.runtime.update

import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpResponse
import kotlinx.coroutines.CompletableDeferred

/** Скриптуемый HTTP-клиент: отдаёт чанки, затем успех/ошибку/ожидание. */
class ScriptedUpdateHttpClient(
    private val chunks: List<ByteArray> = emptyList(),
    private val outcome: Result<Unit> = Result.success(Unit),
    private val hangAfterChunks: CompletableDeferred<Unit>? = null,
    private val onFirstChunkSent: CompletableDeferred<Unit>? = null,
) : UpdateHttpClient {
    var downloads = 0

    override suspend fun get(url: String, headers: Map<String, String>) = UpdateHttpResponse(200, emptyMap(), "")

    override suspend fun downloadStream(
        url: String,
        headers: Map<String, String>,
        onChunk: suspend (ByteArray, Long, Long) -> Unit,
    ): Result<Unit> {
        downloads++
        val total = chunks.sumOf { it.size }.toLong()
        var read = 0L
        for (c in chunks) {
            read += c.size
            onChunk(c, read, total)
            onFirstChunkSent?.complete(Unit)
        }
        hangAfterChunks?.await()
        return outcome
    }
}
