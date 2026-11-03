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
        shutdownHook: suspend () -> Unit,
        browserOpener: (String) -> Unit,
    ): UpdateApplier =
        when (platform) {
            TargetPlatform.WINDOWS_X64 -> WindowsMsiApplier(httpClient(), tempDir, shutdownHook)
            else -> DesktopNotifyApplier(browserOpener)
        }
}
