package dev.mimir.desktop.update

import dev.mimir.core.update.TargetPlatform
import dev.mimir.core.update.UpdateApplier
import dev.mimir.core.update.UpdateHttpClient
import java.nio.file.Path

/** Определяет цель обновления по `os.name` / `os.arch` (значения передаются снаружи — функция чистая). */
fun detectTargetPlatform(osName: String, osArch: String): TargetPlatform {
    val os = osName.lowercase()
    val arch = osArch.lowercase()
    val x64 = arch == "amd64" || arch == "x86_64"
    val arm64 = arch == "aarch64" || arch == "arm64"
    return when {
        os.contains("windows") && x64 -> TargetPlatform.WINDOWS_X64
        os.contains("windows") && arm64 -> TargetPlatform.WINDOWS_ARM64
        os.contains("linux") && x64 -> TargetPlatform.LINUX_X64
        os.contains("linux") && arm64 -> TargetPlatform.LINUX_ARM64
        else -> TargetPlatform.UNKNOWN
    }
}

/** Стратегия выбора аппликера: автоустановка только там, где есть MSI (Windows x64), иначе уведомление. */
object DesktopApplierFactory {
    fun create(
        platform: TargetPlatform,
        httpClient: () -> UpdateHttpClient,
        tempDir: Path,
        host: HostLifecycle,
        browserOpener: (String) -> Unit,
        exit: (Int) -> Unit = ::delayedExit,
    ): UpdateApplier =
        when (platform) {
            TargetPlatform.WINDOWS_X64 -> WindowsMsiApplier(httpClient(), tempDir, host, exit = exit)
            else -> DesktopNotifyApplier(browserOpener)
        }
}

/**
 * Выход из процесса вне корутины запроса: короткая пауза даёт HTTP-ответу (если вызов пришёл через API) уйти клиенту,
 * а обработчику — завершиться, прежде чем процесс остановится.
 */
fun delayedExit(code: Int, graceMillis: Long = 500) {
    Thread({
        Thread.sleep(graceMillis)
        kotlin.system.exitProcess(code)
    }, "mimir-update-exit").apply { isDaemon = false }.start()
}

/** Удаляет брошенные загрузки `mimir-update-*.msi` старше [maxAge]: остаются после падения процесса. */
fun sweepStaleUpdateFiles(
    tempDir: Path,
    maxAge: java.time.Duration = java.time.Duration.ofDays(1),
    now: java.time.Instant = java.time.Instant.now(),
): Int {
    return dev.mimir.runtime.update.VerifiedFileDownloader.sweepStale(tempDir, "mimir-update-*.msi", maxAge, now)
}
