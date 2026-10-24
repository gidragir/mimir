package dev.mimir.drive.upload

import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

class DriveUploader(
    private val client: DriveHttpClient,
    private val pathResolver: DrivePathResolver,
    private val resumableThresholdBytes: Long = 5 * 1024 * 1024,
    private val chunkSize: Int = 1024 * 1024,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Отправляет [data] в Drive, не читая её целиком в память.
     *
     * До [resumableThresholdBytes] байт читаются в буфер; если после этого источник исчерпан
     * (размер ≤ порога, включая пустой файл) — один multipart-запрос. Иначе открывается resumable-сессия
     * без `X-Upload-Content-Length`, и данные уходят блоками [chunkSize] (кратно 256 КиБ) с
     * `Content-Range: bytes a-b/` + звёздочка (размер неизвестен); последний блок несёт итоговый размер. В памяти, кроме начального
     * буфера (не больше порога), — только текущий блок.
     */
    suspend fun upload(path: RemotePath, data: Source): RemoteEntry {
        val head = Buffer()
        while (head.size < resumableThresholdBytes && !data.exhausted()) {
            data.readAtMostTo(head, resumableThresholdBytes - head.size)
        }
        val small = data.exhausted()

        val parentFolderId = pathResolver.resolveOrCreateFolderPath(RemotePath.of(path.segments.dropLast(1)))
        val existingFile = pathResolver.resolvePath(path)
        val fileName = path.segments.last()

        var sentBytes = 0L
        val uploadedFile = if (small) {
            val bytes = head.readByteArray()
            sentBytes = bytes.size.toLong()
            uploadSimple(parentFolderId, existingFile, fileName, bytes)
        } else {
            uploadResumable(parentFolderId, existingFile, fileName, head, data) { sentBytes = it }
        }

        pathResolver.invalidate(path)

        val instant = uploadedFile.modifiedTime?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                Clock.System.now()
            }
        } ?: Clock.System.now()

        return RemoteEntry(
            path = path,
            size = uploadedFile.size ?: sentBytes,
            modifiedAt = instant,
            revision = uploadedFile.md5Checksum ?: uploadedFile.id,
            isDirectory = false,
        )
    }

    private suspend fun uploadSimple(
        parentFolderId: String,
        existingFile: GoogleDriveFile?,
        fileName: String,
        bytes: ByteArray,
    ): GoogleDriveFile {
        val metadata = if (existingFile != null) {
            FileMetadata(name = fileName)
        } else {
            FileMetadata(name = fileName, parents = listOf(parentFolderId))
        }
        val metadataJson = json.encodeToString(metadata)

        val url = if (existingFile != null) {
            "https://www.googleapis.com/upload/drive/v3/files/${existingFile.id}?uploadType=multipart"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        }

        val parts = formData {
            append("metadata", metadataJson, Headers.build {
                append(HttpHeaders.ContentType, "application/json; charset=UTF-8")
            })
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream")
            })
        }

        val response = client.executeWithRetry { token ->
            client.httpClient.submitFormWithBinaryData(
                url = url,
                formData = parts,
            ) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status, "Simple upload failed: ${response.bodyAsText()}")
        }

        return response.body<GoogleDriveFile>()
    }

    private suspend fun uploadResumable(
        parentFolderId: String,
        existingFile: GoogleDriveFile?,
        fileName: String,
        head: Buffer,
        data: Source,
        onTotalKnown: (Long) -> Unit,
    ): GoogleDriveFile {
        val metadata = if (existingFile != null) {
            FileMetadata(name = fileName)
        } else {
            FileMetadata(name = fileName, parents = listOf(parentFolderId))
        }
        val metadataJson = json.encodeToString(metadata)

        val initUrl = if (existingFile != null) {
            "https://www.googleapis.com/upload/drive/v3/files/${existingFile.id}?uploadType=resumable"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        }

        val initResponse = client.executeWithRetry { token ->
            val configure: HttpRequestBuilder.() -> Unit = {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Upload-Content-Type", "application/octet-stream")
                contentType(ContentType.Application.Json)
                setBody(metadataJson)
            }
            if (existingFile != null) client.httpClient.patch(initUrl, configure) else client.httpClient.post(initUrl, configure)
        }

        if (!initResponse.status.isSuccess()) {
            throw DriveHttpException(initResponse.status, "Resumable upload session init failed: ${initResponse.bodyAsText()}")
        }

        val sessionUri = initResponse.headers[HttpHeaders.Location]
            ?: throw IllegalStateException("Google Drive did not return resumable upload session Location header")

        val chunk = ByteArray(chunkSize)
        // Сначала отдаётся накопленный при проверке порога буфер, затем — сам источник.
        fun fill(): Int {
            var filled = 0
            while (filled < chunk.size) {
                val read = if (!head.exhausted()) {
                    head.readAtMostTo(chunk, filled, chunk.size)
                } else {
                    data.readAtMostTo(chunk, filled, chunk.size)
                }
                if (read < 0) break
                filled += read
            }
            return filled
        }

        var chunkStart = 0L
        while (true) {
            val chunkLen = fill()
            val isLast = head.exhausted() && data.exhausted()
            val chunkEnd = chunkStart + chunkLen // исключительно
            val total: Long? = if (isLast) chunkEnd else null
            if (total != null) onTotalKnown(total)

            var offset = chunkStart // первый ещё не подтверждённый байт
            var failures = 0
            while (offset < chunkEnd) {
                val from = (offset - chunkStart).toInt()
                val body = if (from == 0 && chunkLen == chunk.size) chunk else chunk.copyOfRange(from, chunkLen)
                val contentRange = "bytes $offset-${chunkEnd - 1}/${total ?: "*"}"

                val outcome: ChunkOutcome = try {
                    val response = client.executeWithRetry { token ->
                        client.httpClient.put(sessionUri) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header("Content-Range", contentRange)
                            setBody(body)
                        }
                    }
                    when {
                        response.status.value == 308 -> ChunkOutcome.Acked(ackedOffset(response.headers[HttpHeaders.Range]))
                        response.status.isSuccess() -> ChunkOutcome.Finished(response.body<GoogleDriveFile>())
                        else -> ChunkOutcome.Failed(DriveHttpException(response.status, "Resumable chunk upload failed: ${response.bodyAsText()}"))
                    }
                } catch (e: RemoteUnavailableException) {
                    ChunkOutcome.Failed(e)
                }

                when (outcome) {
                    is ChunkOutcome.Finished -> {
                        if (total == null) {
                            throw IllegalStateException("Google Drive finished the upload before the source was exhausted")
                        }
                        return outcome.file
                    }
                    is ChunkOutcome.Acked -> {
                        if (outcome.offset < chunkStart) {
                            throw IllegalStateException("Google Drive lost bytes before the current chunk (acknowledged ${outcome.offset}, chunk starts at $chunkStart)")
                        }
                        offset = outcome.offset
                    }
                    is ChunkOutcome.Failed -> {
                        if (++failures > MAX_CHUNK_RECOVERIES) throw outcome.error
                        when (val status = queryResumableStatus(sessionUri, total)) {
                            null -> throw outcome.error
                            is SessionStatus.Completed -> {
                                if (total == null) {
                                    throw IllegalStateException("Google Drive finished the upload before the source was exhausted")
                                }
                                return status.file
                            }
                            is SessionStatus.Received -> {
                                if (status.offset < chunkStart) {
                                    throw IllegalStateException("Google Drive lost bytes before the current chunk (acknowledged ${status.offset}, chunk starts at $chunkStart)")
                                }
                                offset = status.offset
                            }
                        }
                    }
                }
            }

            if (isLast) {
                // Блок подтверждён (308), но итогового ответа с файлом нет: спросим сессию с известным размером.
                return when (val status = queryResumableStatus(sessionUri, total)) {
                    is SessionStatus.Completed -> status.file
                    else -> throw IllegalStateException("Resumable upload completed loop without final file response")
                }
            }
            chunkStart = chunkEnd
        }
    }

    /** Первый не принятый Drive байт по заголовку `Range: bytes=0-x`; без заголовка принято 0 байт. */
    private fun ackedOffset(rangeHeader: String?): Long {
        val last = rangeHeader?.substringAfterLast('-', "")?.toLongOrNull() ?: return 0L
        return last + 1
    }

    // Запрос состояния: Content-Range со звёздочкой вместо диапазона и размера (или с итоговым размером, если он известен).
    private suspend fun queryResumableStatus(sessionUri: String, total: Long?): SessionStatus? {
        return try {
            val response = client.executeWithRetry { token ->
                client.httpClient.put(sessionUri) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("Content-Range", "bytes */${total ?: "*"}")
                }
            }
            when {
                response.status.value == 308 -> SessionStatus.Received(ackedOffset(response.headers[HttpHeaders.Range]))
                response.status.isSuccess() && total != null -> SessionStatus.Completed(response.body<GoogleDriveFile>())
                else -> null
            }
        } catch (_: RemoteUnavailableException) {
            null
        }
    }

    private sealed interface ChunkOutcome {
        data class Acked(val offset: Long) : ChunkOutcome
        data class Finished(val file: GoogleDriveFile) : ChunkOutcome
        data class Failed(val error: Exception) : ChunkOutcome
    }

    private sealed interface SessionStatus {
        data class Received(val offset: Long) : SessionStatus
        data class Completed(val file: GoogleDriveFile) : SessionStatus
    }

    private companion object {
        /** Подряд идущие сбои одного блока, после которых загрузка прекращается. */
        const val MAX_CHUNK_RECOVERIES = 5
    }

    @Serializable
    private data class FileMetadata(
        val name: String,
        val parents: List<String>? = null,
    )
}
