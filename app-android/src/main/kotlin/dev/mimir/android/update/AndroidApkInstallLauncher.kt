package dev.mimir.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.mimir.runtime.update.ApkInstallLauncher
import java.nio.file.Path

/**
 * Тонкая оболочка над фреймворком Android для [dev.mimir.runtime.update.ApkInstallApplier]: разрешение на установку,
 * экран настроек и запуск системного установщика через `FileProvider`. Логики здесь нет, поэтому JVM-тестов нет.
 */
class AndroidApkInstallLauncher(context: Context) : ApkInstallLauncher {
    private val context: Context = context.applicationContext ?: context

    override fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun launchInstaller(apk: Path) {
        val uri = FileProvider.getUriForFile(context, authority(context), apk.toFile())
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** Должен совпадать с `android:authorities` провайдера в манифесте (`${applicationId}.updates`). */
        fun authority(context: Context): String = "${context.packageName}.updates"
    }
}
