package dev.mimir.runtime.update

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateRelease
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/** Платформенные вызовы Android за интерфейсом: логика установщика тестируется на JVM без SDK. */
interface ApkInstallLauncher {
    /** Можно ли приложению запускать установку (Android 8+: «установка неизвестных приложений»). */
    fun canRequestInstalls(): Boolean

    /** Открывает системный экран, где пользователь разрешает установку из Mimir. */
    fun openInstallPermissionSettings()

    /** Запускает системный установщик для [apk]; бросает исключение, если запустить не удалось. */
    fun launchInstaller(apk: Path)
}

/** Остановка и возобновление фоновой синхронизации вокруг установки (обычно [dev.mimir.runtime.MimirRuntime]). */
interface UpdateQuiesce {
    /** `false` — синхронизация не закончилась, всё осталось как было. */
    suspend fun quiesce(): Boolean

    suspend fun resume()
}

/**
 * Android: скачивает APK в [updatesDir] (кэш приложения), проверяет SHA-256 (обязательно) и отдаёт файл системному
 * установщику через [ApkInstallLauncher].
 *
 * Порядок применения: разрешение на установку -> [UpdateQuiesce.quiesce] -> повторная проверка хэша (файл могли
 * подменить после скачивания) -> запуск. Любой сбой до успешного запуска возвращает синхронизацию в работу.
 * Процесс завершает сама ОС при установке, поэтому явного выхода нет.
 */
class ApkInstallApplier(
    httpClient: UpdateHttpClient,
    private val updatesDir: Path,
    private val launcher: ApkInstallLauncher,
    private val quiesce: UpdateQuiesce,
    private val ioDispatcher: CoroutineDispatcher,
) : UpdateApplier {
    override val capability = ApplierCapability.DOWNLOAD_AND_LAUNCH

    private val downloader = VerifiedFileDownloader(httpClient, updatesDir, "mimir-update-", ".apk", ioDispatcher)

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> = downloader.download(asset, onProgress)

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
        val local = downloadedAsset as? DownloadedAsset.LocalFile
            ?: return UpdateApplyResult.Failed("Android installer needs a downloaded file")
        val expected = local.asset.expectedSha256?.takeIf { it.isNotBlank() }
            ?: return UpdateApplyResult.Failed("Cannot install ${local.asset.name}: no checksum published")
        val path = Path.of(local.localPath).toAbsolutePath().normalize()
        if (path.parent != updatesDir.toAbsolutePath().normalize()) {
            return UpdateApplyResult.Failed("Installer is outside the update directory")
        }
        if (!launcher.canRequestInstalls()) {
            try {
                launcher.openInstallPermissionSettings()
            } catch (_: Exception) {
                // Экран настроек недоступен: пользователь всё равно получает текст ниже.
            }
            return UpdateApplyResult.Failed("Allow installing apps from Mimir in the system settings that just opened, then try again")
        }
        // Остановка рантайма отменяет корутины; запуск установщика обязан завершиться в любом случае.
        return withContext(NonCancellable) {
            if (!quiesce.quiesce()) {
                return@withContext UpdateApplyResult.Failed("A sync is still running, try again later")
            }
            var launched = false
            try {
                val actual = try {
                    withContext(ioDispatcher) { VerifiedFileDownloader.sha256Of(path) }
                } catch (e: IOException) {
                    return@withContext UpdateApplyResult.Failed("Installer file is unavailable", e)
                }
                if (!actual.equals(expected, ignoreCase = true)) {
                    VerifiedFileDownloader.deleteQuietly(path)
                    return@withContext UpdateApplyResult.Failed("Installer checksum changed after download; update cancelled")
                }
                try {
                    launcher.launchInstaller(path)
                    launched = true
                } catch (e: Exception) {
                    return@withContext UpdateApplyResult.Failed("Could not start the installer: ${e.message}", e)
                }
            } finally {
                if (!launched) quiesce.resume()
            }
            UpdateApplyResult.LaunchedInstaller("Installer started for ${local.asset.name}")
        }
    }
}
