package dev.mimir.desktop.update

import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateRelease
import dev.mimir.runtime.update.VerifiedFileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess

/** Жизненный цикл встроенного демона вокруг установки. */
interface HostLifecycle {
    /** Останавливает фоновую работу и ждёт конец синхронизации; `false` — синхронизация не закончилась, всё осталось как было. */
    suspend fun quiesce(): Boolean

    /** Отменяет [quiesce]. */
    suspend fun resume()

    /** Окончательно закрывает демон (после успешного запуска установщика). Не должен блокировать вызывающего. */
    suspend fun shutdown()
}

/**
 * Windows: скачивает MSI во временный каталог, проверяет SHA-256 (обязательно) и запускает `msiexec /i <msi> /passive`.
 *
 * Проверка хэша идёт потоком при скачивании и повторно непосредственно перед запуском (защита от подмены файла
 * между скачиванием и применением). Любой сбой удаляет файл. Установщик без опубликованной контрольной суммы
 * не запускается никогда.
 *
 * @param processLauncher запускает процесс (аргументы списком, не строкой оболочки); бросает [IOException] при неудаче.
 * Порядок применения: [HostLifecycle.quiesce] -> проверка хэша -> запуск -> [HostLifecycle.shutdown] -> exit.
 * Любой сбой до успешного запуска вызывает [HostLifecycle.resume]: демон остаётся рабочим.
 *
 * @param host остановка/возобновление встроенного демона вокруг установки (MSI заменяет файлы приложения).
 * @param exit завершает процесс после успешного запуска установщика.
 */
class WindowsMsiApplier(
    private val httpClient: UpdateHttpClient,
    private val tempDir: Path,
    private val host: HostLifecycle,
    private val processLauncher: (List<String>) -> Unit = ::startProcess,
    private val exit: (Int) -> Unit = { exitProcess(it) },
) : UpdateApplier {
    override val capability = ApplierCapability.DOWNLOAD_AND_LAUNCH

    private val downloader = VerifiedFileDownloader(httpClient, tempDir, "mimir-update-", ".msi", Dispatchers.IO)

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> {
        return downloader.download(asset, onProgress)
    }

    override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult {
        val local = downloadedAsset as? DownloadedAsset.LocalFile
            ?: return UpdateApplyResult.Failed("Windows installer needs a downloaded file")
        val expected = local.asset.expectedSha256?.takeIf { it.isNotBlank() }
            ?: return UpdateApplyResult.Failed("Cannot install ${local.asset.name}: no checksum published")
        val path = Path.of(local.localPath).toAbsolutePath().normalize()
        if (path.parent != tempDir.toAbsolutePath().normalize()) {
            return UpdateApplyResult.Failed("Installer is outside the update directory")
        }
        // Остановка демона отменяет корутины рантайма, а установщик обязан стартовать в любом случае.
        return withContext(NonCancellable) {
            if (!host.quiesce()) {
                return@withContext UpdateApplyResult.Failed("A sync is still running, try again later")
            }
            var launched = false
            try {
                val actual = try {
                    withContext(Dispatchers.IO) { VerifiedFileDownloader.sha256Of(path) }
                } catch (e: IOException) {
                    return@withContext UpdateApplyResult.Failed("Installer file is unavailable", e)
                }
                if (!actual.equals(expected, ignoreCase = true)) {
                    VerifiedFileDownloader.deleteQuietly(path)
                    return@withContext UpdateApplyResult.Failed("Installer checksum changed after download; update cancelled")
                }
                try {
                    processLauncher(listOf("msiexec", "/i", path.toString(), "/passive"))
                    launched = true
                } catch (e: Exception) {
                    return@withContext UpdateApplyResult.Failed("Could not start the installer: ${e.message}", e)
                }
            } finally {
                if (!launched) host.resume()
            }
            host.shutdown()
            exit(0)
            UpdateApplyResult.LaunchedInstaller("Installer started for ${local.asset.name}")
        }
    }
}

private fun startProcess(command: List<String>) {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
