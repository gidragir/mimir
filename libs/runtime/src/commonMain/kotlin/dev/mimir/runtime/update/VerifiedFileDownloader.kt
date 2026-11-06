package dev.mimir.runtime.update

import dev.mimir.core.Sha256
import dev.mimir.core.update.DownloadedAsset
import dev.mimir.core.update.UpdateAsset
import dev.mimir.core.update.UpdateHttpClient
import dev.mimir.core.update.UpdateHttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val downloadLog = Logger.getLogger(VerifiedFileDownloader::class.java.name)

/**
 * Скачивает ассет обновления потоком в файл каталога [dir], считая SHA-256 на лету.
 *
 * Контрольная сумма обязательна: ассет без неё не скачивается вовсе. Любой сбой, отмена или несовпадение хэша
 * удаляют частично записанный файл. Общая часть установщиков, которые запускают скачанный файл (MSI, APK).
 *
 * Сетевой сбой, обрыв («Incomplete download») и 5xx повторяются до [maxAttempts] раз с паузой
 * [retryBaseDelay], 2×, 4× …; каждая попытка пишет новый файл с нуля (докачки нет). Не повторяются 4xx,
 * ошибки диска и несовпадение хэша: повтор их не исправит.
 */
class VerifiedFileDownloader(
    private val httpClient: UpdateHttpClient,
    private val dir: Path,
    private val prefix: String,
    private val suffix: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val maxAttempts: Int = 3,
    private val retryBaseDelay: Duration = 1.seconds,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** [onProgress] получает (доля 0..1, прочитано байт, всего байт или -1). */
    suspend fun download(asset: UpdateAsset, onProgress: (Float, Long, Long) -> Unit): Result<DownloadedAsset.LocalFile> {
        val expected = asset.expectedSha256?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Cannot install ${asset.name}: no checksum published"))
        var attempt = 1
        while (true) {
            val result = downloadOnce(asset, expected, onProgress)
            val error = result.exceptionOrNull() ?: return result
            if (attempt >= maxAttempts || !isRetryable(error)) return result
            val pause = retryBaseDelay * (1 shl (attempt - 1))
            downloadLog.log(Level.WARNING, "Update download attempt $attempt/$maxAttempts failed (${asset.name}): ${error.message}; retrying in $pause", error)
            delay(pause)
            attempt++
        }
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is DiskWriteException, is ChecksumMismatchException -> false
        is UpdateHttpStatusException -> error.statusCode >= 500
        else -> true
    }

    /** Одна попытка в новый временный файл; при любом исходе, кроме успеха, файл удалён. */
    private suspend fun downloadOnce(
        asset: UpdateAsset,
        expected: String,
        onProgress: (Float, Long, Long) -> Unit,
    ): Result<DownloadedAsset.LocalFile> {
        var file: Path? = null
        try {
            val target = withContext(ioDispatcher) {
                disk {
                    Files.createDirectories(dir)
                    Files.createTempFile(dir, prefix, suffix)
                }
            }
            file = target
            val hasher = Sha256.Hasher()
            val result = withContext(ioDispatcher) {
                disk { Files.newOutputStream(target) }.use { out ->
                    httpClient.downloadStream(asset.downloadUrl) { chunk, read, total ->
                        disk { out.write(chunk) }
                        hasher.update(chunk)
                        val fraction = if (total > 0) (read.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
                        onProgress(fraction, read, total)
                    }
                }
            }
            result.exceptionOrNull()?.let { throw it }
            val actual = hasher.digestHex()
            if (!actual.equals(expected, ignoreCase = true)) throw ChecksumMismatchException("SHA-256 mismatch for ${asset.name}")
            return Result.success(DownloadedAsset.LocalFile(target.toString(), actual, asset))
        } catch (e: CancellationException) {
            file?.let(::deleteQuietly)
            throw e
        } catch (e: Throwable) {
            file?.let(::deleteQuietly)
            return Result.failure(e)
        }
    }

    /** Скачанный файл не совпал с опубликованным хэшем. */
    class ChecksumMismatchException(message: String) : IllegalStateException(message)

    /** Сбой диска (нет места, нет прав, путь занят файлом) помечается для `classifyUpdateError` → `DISK`. */
    private inline fun <T> disk(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw DiskWriteException(e)
        }

    /** Ошибка записи временного файла обновления; повторять скачивание при ней бессмысленно. */
    class DiskWriteException(cause: IOException) : IOException("Cannot write update file: ${cause.message ?: cause::class.java.simpleName}", cause)

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

        /** Удаляет брошенные загрузки по маске [glob] старше [maxAge] (остаются после падения процесса); возвращает число удалённых. */
        fun sweepStale(
            dir: Path,
            glob: String,
            maxAge: java.time.Duration = java.time.Duration.ofDays(1),
            now: java.time.Instant = java.time.Instant.now(),
        ): Int {
            if (!Files.isDirectory(dir)) return 0
            var deleted = 0
            Files.newDirectoryStream(dir, glob).use { files ->
                for (f in files) {
                    try {
                        val modified = Files.getLastModifiedTime(f).toInstant()
                        if (modified.plus(maxAge).isBefore(now) && Files.deleteIfExists(f)) deleted++
                    } catch (_: IOException) {
                    }
                }
            }
            return deleted
        }

        fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
            }
        }
    }
}
