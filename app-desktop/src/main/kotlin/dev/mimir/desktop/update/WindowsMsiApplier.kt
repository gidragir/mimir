package dev.mimir.desktop.update

import dev.mimir.core.Sha256
import dev.mimir.core.update.ApplierCapability
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateApplyResult
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Windows: скачивает MSI во временный каталог, проверяет SHA-256 (обязательно) и запускает `msiexec /i <msi> /passive`.
 *
 * Проверка хэша идёт потоком при скачивании и повторно непосредственно перед запуском (защита от подмены файла
 * между скачиванием и применением). Любой сбой удаляет файл. Установщик без опубликованной контрольной суммы
 * не запускается никогда.
 *
 * @param processLauncher запускает процесс (аргументы списком, не строкой оболочки); бросает [IOException] при неудаче.
 * @param shutdownHook останавливает демон перед установкой (MSI заменяет файлы приложения).
 * @param exit завершает процесс после успешного запуска установщика.
 */
class WindowsMsiApplier(
    private val httpClient: UpdateHttpClient,
    private val tempDir: Path,
    private val shutdownHook: suspend () -> Unit,
    private val processLauncher: (List<String>) -> Unit = ::startProcess,
    private val exit: (Int) -> Unit = { exitProcess(it) },
) : UpdateApplier {
    override val capability = ApplierCapability.DOWNLOAD_AND_LAUNCH

    override suspend fun download(
        asset: UpdateAsset,
        release: UpdateRelease,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset> {
        val expected = asset.expectedSha256?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Cannot install ${asset.name}: no checksum published"))
        var file: Path? = null
        try {
            val target = withContext(Dispatchers.IO) {
                Files.createDirectories(tempDir)
                Files.createTempFile(tempDir, "mimir-update-", ".msi")
            }
            file = target
            val hasher = Sha256.Hasher()
            val result = withContext(Dispatchers.IO) {
                Files.newOutputStream(target).use { out ->
                    httpClient.downloadStream(asset.downloadUrl) { chunk, read, total ->
                        out.write(chunk)
                        hasher.update(chunk)
                        val fraction = if (total > 0) (read.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
                        onProgress(fraction, read, total)
                    }
                }
            }
            result.exceptionOrNull()?.let { throw it }
            val actual = hasher.digestHex()
            check(actual.equals(expected, ignoreCase = true)) { "SHA-256 mismatch for ${asset.name}" }
            return Result.success(DownloadedAsset.LocalFile(target.toString(), actual, asset))
        } catch (e: CancellationException) {
            file?.let(::deleteQuietly)
            throw e
        } catch (e: Throwable) {
            file?.let(::deleteQuietly)
            return Result.failure(e)
        }
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
            val actual = try {
                withContext(Dispatchers.IO) { sha256Of(path) }
            } catch (e: IOException) {
                return@withContext UpdateApplyResult.Failed("Installer file is unavailable", e)
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                deleteQuietly(path)
                return@withContext UpdateApplyResult.Failed("Installer checksum changed after download; update cancelled")
            }
            try {
                shutdownHook()
                processLauncher(listOf("msiexec", "/i", path.toString(), "/passive"))
            } catch (e: Exception) {
                return@withContext UpdateApplyResult.Failed("Could not start the installer: ${e.message}", e)
            }
            exit(0)
            UpdateApplyResult.LaunchedInstaller("Installer started for ${local.asset.name}")
        }
    }

    private fun sha256Of(path: Path): String {
        val hasher = Sha256.Hasher()
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hasher.update(buffer, 0, n)
            }
        }
        return hasher.digestHex()
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
        }
    }
}

private fun startProcess(command: List<String>) {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
