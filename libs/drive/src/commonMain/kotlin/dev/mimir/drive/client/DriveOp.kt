package dev.mimir.drive.client

enum class DriveOp(val wire: String, val isWrite: Boolean) {
    ListQuery("list-q", false),
    ListAllPage("list-all-page", false),
    ChangesPage("changes-page", false),
    StartToken("start-token", false),
    FolderCreate("folder-create", true),
    UploadMultipart("upload-multipart", true),
    UploadInit("upload-init", true),
    UploadChunk("upload-chunk", false),
    UploadStatus("upload-status", false),
    GetMedia("get-media", false),
    GetMeta("get-meta", false),
    Trash("trash", true),
    Other("other", false),
}
