package dev.mimir.drive

import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.drive.api.GoogleDriveFile
import dev.mimir.drive.api.GoogleDriveFileList
import dev.mimir.drive.client.DriveHttpClient
import dev.mimir.drive.client.DriveHttpException
import dev.mimir.drive.path.DrivePathResolver
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.time.Clock
import kotlin.time.Instant

class GoogleDriveStorage(
    private val client: DriveHttpClient? = null,
    private val pathResolver: DrivePathResolver? = null,
) : RemoteStorage {

    private fun ensureConfigured(): Pair<DriveHttpClient, DrivePathResolver> {
        val c = client ?: throw NotImplementedError("Google Drive adapter is not configured")
        val r = pathResolver ?: throw NotImplementedError("Google Drive adapter is not configured")
        return c to r
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> {
        val (client, pathResolver) = ensureConfigured()
        val folderId = if (dir.value.isEmpty()) {
            pathResolver.resolveRootFolderId()
        } else {
            val folderFile = pathResolver.resolvePath(dir) ?: return emptyList()
            if (!folderFile.isDirectory) return emptyList()
            folderFile.id
        }

        val result = mutableListOf<RemoteEntry>()
        var pageToken: String? = null

        do {
            val response = client.executeWithRetry { token ->
                client.httpClient.get("https://www.googleapis.com/drive/v3/files") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("q", "'$folderId' in parents and trashed = false")
                    parameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed)")
                    parameter("pageSize", 100)
                    if (pageToken != null) {
                        parameter("pageToken", pageToken)
                    }
                }
            }

            if (!response.status.isSuccess()) {
                throw DriveHttpException(response.status, "Failed to list folder $folderId")
            }

            val list = response.body<GoogleDriveFileList>()
            for (file in list.files) {
                val childPath = if (dir.value.isEmpty()) {
                    RemotePath(file.name)
                } else {
                    RemotePath("${dir.value}/${file.name}")
                }
                result.add(toRemoteEntry(file, childPath))
            }

            pageToken = list.nextPageToken
        } while (pageToken != null)

        return result
    }

    override suspend fun stat(path: RemotePath): RemoteEntry? {
        val (_, pathResolver) = ensureConfigured()
        val file = pathResolver.resolvePath(path) ?: return null
        return toRemoteEntry(file, path)
    }

    override suspend fun read(path: RemotePath): Source {
        val (client, pathResolver) = ensureConfigured()
        val file = pathResolver.resolvePath(path)
            ?: throw NoSuchElementException("No such file in Drive: ${path.value}")

        val response = client.executeWithRetry { token ->
            client.httpClient.get("https://www.googleapis.com/drive/v3/files/${file.id}") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("alt", "media")
            }
        }

        if (!response.status.isSuccess()) {
            throw DriveHttpException(response.status, "Failed to read file ${file.id}")
        }

        val bytes = response.body<ByteArray>()
        return Buffer().also { it.write(bytes) }
    }

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry {
        ensureConfigured()
        throw NotImplementedError("Google Drive write will be implemented in Task 7")
    }

    override suspend fun delete(path: RemotePath) {
        val (client, pathResolver) = ensureConfigured()
        val file = pathResolver.resolvePath(path) ?: return
        val response = client.executeWithRetry { token ->
            client.httpClient.delete("https://www.googleapis.com/drive/v3/files/${file.id}") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        if (!response.status.isSuccess() && response.status.value != 404) {
            throw DriveHttpException(response.status, "Failed to delete file ${file.id}")
        }
        pathResolver.invalidate(path)
    }

    private fun toRemoteEntry(file: GoogleDriveFile, path: RemotePath): RemoteEntry {
        val instant = file.modifiedTime?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                Clock.System.now()
            }
        } ?: Clock.System.now()

        return RemoteEntry(
            path = path,
            size = file.size ?: 0L,
            modifiedAt = instant,
            revision = file.md5Checksum ?: file.id,
            isDirectory = file.isDirectory,
        )
    }
}
