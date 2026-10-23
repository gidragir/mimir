package dev.mimir.drive.upload

import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.call.body
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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

    suspend fun upload(path: RemotePath, data: Source): RemoteEntry {
        val bytes = data.readByteArray()
        val totalSize = bytes.size.toLong()
        val parentFolderId = pathResolver.resolveOrCreateFolderPath(RemotePath.of(path.segments.dropLast(1)))
        val existingFile = pathResolver.resolvePath(path)
        val fileName = path.segments.last()

        val uploadedFile = if (totalSize < resumableThresholdBytes) {
            uploadSimple(parentFolderId, existingFile, fileName, bytes)
        } else {
            uploadResumable(parentFolderId, existingFile, fileName, bytes)
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
            size = uploadedFile.size ?: totalSize,
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
        bytes: ByteArray,
    ): GoogleDriveFile {
        val totalSize = bytes.size.toLong()
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
            if (existingFile != null) {
                client.httpClient.patch(initUrl) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Upload-Content-Type", "application/octet-stream")
                    header("X-Upload-Content-Length", totalSize.toString())
                    contentType(ContentType.Application.Json)
                    setBody(metadataJson)
                }
            } else {
                client.httpClient.post(initUrl) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Upload-Content-Type", "application/octet-stream")
                    header("X-Upload-Content-Length", totalSize.toString())
                    contentType(ContentType.Application.Json)
                    setBody(metadataJson)
                }
            }
        }

        if (!initResponse.status.isSuccess()) {
            throw DriveHttpException(initResponse.status, "Resumable upload session init failed: ${initResponse.bodyAsText()}")
        }

        val sessionUri = initResponse.headers[HttpHeaders.Location]
            ?: throw IllegalStateException("Google Drive did not return resumable upload session Location header")

        var currentOffset = 0L

        while (currentOffset < totalSize) {
            val remaining = (totalSize - currentOffset).toInt()
            val currentChunkSize = minOf(chunkSize, remaining)
            val endByte = currentOffset + currentChunkSize - 1
            val chunkBytes = bytes.copyOfRange(currentOffset.toInt(), (currentOffset + currentChunkSize).toInt())

            val uploadResponse = try {
                client.executeWithRetry { token ->
                    client.httpClient.put(sessionUri) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header("Content-Range", "bytes $currentOffset-$endByte/$totalSize")
                        header(HttpHeaders.ContentLength, currentChunkSize.toString())
                        setBody(chunkBytes)
                    }
                }
            } catch (e: Exception) {
                val recoveredOffset = queryResumableStatus(sessionUri, totalSize)
                if (recoveredOffset != null) {
                    currentOffset = recoveredOffset
                    continue
                }
                throw e
            }

            if (uploadResponse.status.value == 308) {
                val rangeHeader = uploadResponse.headers["Range"]
                if (rangeHeader != null && rangeHeader.startsWith("bytes=0-")) {
                    val lastAck = rangeHeader.removePrefix("bytes=0-").toLongOrNull() ?: currentOffset
                    currentOffset = lastAck + 1
                } else {
                    currentOffset = endByte + 1
                }
            } else if (uploadResponse.status.isSuccess()) {
                return uploadResponse.body<GoogleDriveFile>()
            } else {
                val recoveredOffset = queryResumableStatus(sessionUri, totalSize)
                if (recoveredOffset != null) {
                    currentOffset = recoveredOffset
                    continue
                }
                throw DriveHttpException(uploadResponse.status, "Resumable chunk upload failed: ${uploadResponse.bodyAsText()}")
            }
        }

        throw IllegalStateException("Resumable upload completed loop without final file response")
    }

    private suspend fun queryResumableStatus(sessionUri: String, totalSize: Long): Long? {
        return try {
            val statusQuery = client.executeWithRetry { token ->
                client.httpClient.put(sessionUri) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("Content-Range", "bytes */$totalSize")
                }
            }
            if (statusQuery.status.value == 308) {
                val rangeHeader = statusQuery.headers["Range"]
                if (rangeHeader != null && rangeHeader.startsWith("bytes=0-")) {
                    (rangeHeader.removePrefix("bytes=0-").toLongOrNull() ?: -1L) + 1L
                } else {
                    0L
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class FileMetadata(
        val name: String,
        val parents: List<String>? = null,
    )
}
