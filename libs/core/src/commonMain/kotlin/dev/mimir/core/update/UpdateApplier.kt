package dev.mimir.core.update

/** Результат скачивания ассета; форма зависит от [UpdateApplier]. */
sealed interface DownloadedAsset {
    val asset: UpdateAsset

    /** Файл на диске, SHA-256 уже посчитан при скачивании. */
    data class LocalFile(
        val localPath: String,
        val actualSha256: String,
        override val asset: UpdateAsset,
    ) : DownloadedAsset

    /** Набор файлов в памяти (плагин Obsidian, расширение). */
    class MemoryBundle(
        val files: Map<String, ByteArray>,
        override val asset: UpdateAsset,
    ) : DownloadedAsset

    /** Скачивать нечего: только уведомление (Docker, Arch). */
    data class Virtual(override val asset: UpdateAsset) : DownloadedAsset
}

sealed interface UpdateApplyResult {
    data class Success(val message: String, val requiresRestart: Boolean) : UpdateApplyResult

    data class LaunchedInstaller(val message: String) : UpdateApplyResult

    data class NotificationDispatched(val url: String) : UpdateApplyResult

    data class Failed(val error: String, val cause: Throwable? = null) : UpdateApplyResult
}

/** Платформенная часть обновления: скачивание и применение. Отвечает за очистку своих временных файлов. */
interface UpdateApplier {
    val capability: ApplierCapability

    /** [onProgress] получает (доля 0..1, прочитано байт, всего байт). */
    suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset>

    suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult
}
