package dev.mimir.drive

import dev.mimir.core.RemotePath
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.Source

internal class DriveDownloader(
    private val client: DriveHttpClient,
    private val pathResolver: DrivePathResolver,
    private val downloadChunkBytes: Long?,
) {
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun read(path: RemotePath): Source {
        val file = pathResolver.resolvePath(path)
            ?: throw NoSuchElementException("No such file in Drive: ${path.value}")

        // Тело качается в фоне через ограниченный буфер канала: память не зависит от размера файла.
        val channel = ByteChannel()
        val started = CompletableDeferred<Unit>()
        val pump = streamScope.launch {
            try {
                val chunk = downloadChunkBytes
                if (chunk == null) {
                    client.executeStreaming(
                        request = { token ->
                            client.httpClient.prepareGet("https://www.googleapis.com/drive/v3/files/${file.id}") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                                parameter("alt", "media")
                            }
                        },
                    ) { response ->
                        if (!response.status.isSuccess()) {
                            throw DriveHttpException(response.status, "Failed to read file ${file.id}")
                        }
                        started.complete(Unit)
                        response.bodyAsChannel().copyTo(channel)
                    }
                } else {
                    pumpRanged(file.id, chunk, channel, started)
                }
                channel.flushAndClose()
            } catch (e: CancellationException) {
                channel.cancel(e)
                throw e
            } catch (e: Throwable) {
                started.completeExceptionally(e)
                channel.cancel(e)
            }
        }
        try {
            started.await()
        } catch (e: CancellationException) {
            pump.cancel()
            throw e
        }
        return channel.toSource()
    }

    /** Скачивание кусками `Range`: каждый кусок — отдельный запрос с собственным refresh токена. */
    private suspend fun pumpRanged(
        fileId: String,
        chunk: Long,
        channel: ByteChannel,
        started: CompletableDeferred<Unit>,
    ) {
        var from = 0L
        while (true) {
            var done = false
            var received = 0L
            var total: Long? = null
            client.executeStreaming(
                request = { token ->
                    client.httpClient.prepareGet("https://www.googleapis.com/drive/v3/files/$fileId") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(HttpHeaders.Range, "bytes=$from-${from + chunk - 1}")
                        parameter("alt", "media")
                    }
                },
            ) { response ->
                when {
                    response.status.value == 416 -> {
                        started.complete(Unit)
                        done = true
                    }
                    !response.status.isSuccess() ->
                        throw DriveHttpException(response.status, "Failed to read file $fileId")
                    response.status.value != 206 -> {
                        // Сервер проигнорировал Range и отдал всё тело.
                        started.complete(Unit)
                        response.bodyAsChannel().copyTo(channel, Long.MAX_VALUE)
                        done = true
                    }
                    else -> {
                        started.complete(Unit)
                        total = response.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.toLongOrNull()
                        received = response.bodyAsChannel().copyTo(channel, Long.MAX_VALUE)
                    }
                }
            }
            if (done) return
            from += received
            val t = total
            if (received == 0L || (t != null && from >= t) || (t == null && received < chunk)) return
        }
    }
}
