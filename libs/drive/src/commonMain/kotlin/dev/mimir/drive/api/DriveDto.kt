package dev.mimir.drive.api

import kotlinx.serialization.Serializable

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
