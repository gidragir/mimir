package dev.mimir.runtime.update

import dev.mimir.core.Sha256
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Скачивает ассет обновления потоком в файл каталога [dir], считая SHA-256 на лету.
 *
 * Контрольная сумма обязательна: ассет без неё не скачивается вовсе. Любой сбой, отмена или несовпадение хэша
 * удаляют частично записанный файл. Общая часть установщиков, которые запускают скачанный файл (MSI, APK).
 */
class VerifiedFileDownloader(
    private val httpClient: UpdateHttpClient,
    private val dir: Path,
    private val prefix: String,
    private val suffix: String,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** [onProgress] получает (доля 0..1, прочитано байт, всего байт или -1). */
    suspend fun download(asset: UpdateAsset, onProgress: (Float, Long, Long) -> Unit): Result<DownloadedAsset.LocalFile> {
        val expected = asset.expectedSha256?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Cannot install ${asset.name}: no checksum published"))
        var file: Path? = null
        try {
            val target = withContext(ioDispatcher) {
                Files.createDirectories(dir)
                Files.createTempFile(dir, prefix, suffix)
            }
            file = target
            val hasher = Sha256.Hasher()
            val result = withContext(ioDispatcher) {
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

    companion object {
        /** SHA-256 файла (hex), чтение блоками. */
        fun sha256Of(path: Path): String {
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

        fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
            }
        }
    }
}
