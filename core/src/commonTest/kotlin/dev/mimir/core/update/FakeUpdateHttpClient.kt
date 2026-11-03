package dev.mimir.core.update

/** Запрос, записанный [FakeUpdateHttpClient]. */
data class RecordedRequest(val url: String, val headers: Map<String, String>)

/**
 * Фейк [UpdateHttpClient] для тестов обновлений: ответы по URL, исключения по URL,
 * запись всех запросов (с заголовками), настраиваемые чанки для `downloadStream`.
 */
class FakeUpdateHttpClient(
    val responses: MutableMap<String, UpdateHttpResponse> = mutableMapOf(),
    val failures: MutableMap<String, Throwable> = mutableMapOf(),
    /** Чанки, отдаваемые `downloadStream` по URL; нет записи — `Result.failure`. */
    val downloads: MutableMap<String, List<ByteArray>> = mutableMapOf(),
) : UpdateHttpClient {
    val requests = mutableListOf<RecordedRequest>()

    fun requestsTo(url: String): List<RecordedRequest> = requests.filter { it.url == url }

    override suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
        requests += RecordedRequest(url, headers)
        failures[url]?.let { throw it }
        return responses[url] ?: UpdateHttpResponse(404, emptyMap(), "")
    }

    override suspend fun downloadStream(
        url: String,
        headers: Map<String, String>,
        onChunk: suspend (ByteArray, Long, Long) -> Unit,
    ): Result<Unit> {
        requests += RecordedRequest(url, headers)
        failures[url]?.let { return Result.failure(it) }
        val chunks = downloads[url] ?: return Result.failure(IllegalStateException("no download for $url"))
        val total = chunks.sumOf { it.size.toLong() }
        var done = 0L
        for (c in chunks) {
            done += c.size
            onChunk(c, done, total)
        }
        return Result.success(Unit)
    }
}
