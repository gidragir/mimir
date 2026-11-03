package dev.mimir.core.update

data class UpdateHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val bodyText: String,
)

/** Порт HTTP для проверки и загрузки обновлений; реализуется на каждой платформе. */
interface UpdateHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): UpdateHttpResponse

    /**
     * [onChunk] получает (чанк, загружено байт, всего байт или -1 если неизвестно).
     * Массив чанка принадлежит получателю: реализация не переиспользует его после вызова.
     */
    suspend fun downloadStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        onChunk: suspend (ByteArray, Long, Long) -> Unit,
    ): Result<Unit>
}
