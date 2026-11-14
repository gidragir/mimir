package dev.mimir.drive.api

import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

internal val driveJson = Json { ignoreUnknownKeys = true }

fun GoogleDriveFile.toRemoteEntry(path: RemotePath, defaultSize: Long = 0L): RemoteEntry {
    val instant = modifiedTime?.let {
        try {
            Instant.parse(it)
        } catch (_: Exception) {
            Clock.System.now()
        }
    } ?: Clock.System.now()

    return RemoteEntry(
        path = path,
        size = size ?: defaultSize,
        modifiedAt = instant,
        // только md5 содержимого: у папок его нет, и это честное «ревизии нет» (план сравнит size+mtime)
        revision = md5Checksum,
        isDirectory = isDirectory,
    )
}

@Serializable
data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long? = null,
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
    val parents: List<String> = emptyList(),
    val trashed: Boolean = false,
) {
    val isDirectory: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

@Serializable
data class GoogleDriveFileList(
    val files: List<GoogleDriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GoogleStartPageToken(val startPageToken: String)

@Serializable
data class GoogleDriveChange(
    val fileId: String,
    val removed: Boolean = false,
    val file: GoogleDriveFile? = null,
)

@Serializable
data class GoogleDriveChangeList(
    val changes: List<GoogleDriveChange> = emptyList(),
    val newStartPageToken: String? = null,
    val nextPageToken: String? = null,
)

@Serializable
data class GoogleTokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in: Long,
    val token_type: String = "Bearer",
    val scope: String? = null,
)

@Serializable
data class GoogleDeviceCodeResponse(
    val device_code: String,
    val user_code: String,
    val verification_url: String,
    val expires_in: Long,
    val interval: Long = 5,
)

@Serializable
data class GoogleUserInfo(
    val sub: String? = null,
    val email: String? = null,
    val name: String? = null,
    val picture: String? = null,
)
