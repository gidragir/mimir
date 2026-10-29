package dev.mimir.android.platform

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract

object TreeUriPaths {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val ERROR_UNTRANSLATABLE = "Эта папка не на телефоне. Выберите папку во внутренней памяти или на карте памяти"

    fun toPath(treeDocumentId: String, volumes: Map<String, String>): String? {
        val parts = treeDocumentId.split(':', limit = 2)
        val volumeId = parts[0]
        val subPath = parts.getOrNull(1)?.trimStart('/') ?: ""
        val volumeDir = volumes[volumeId] ?: return null
        return if (subPath.isEmpty()) volumeDir else "$volumeDir/$subPath"
    }

    fun toPath(uri: Uri?, volumes: Map<String, String>): String? {
        if (uri == null || uri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            return null
        }
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        } ?: run {
            val path = uri.path ?: return null
            val treePrefix = "/tree/"
            val idx = path.indexOf(treePrefix)
            if (idx >= 0) {
                val raw = path.substring(idx + treePrefix.length).substringBefore("/document/")
                Uri.decode(raw)
            } else {
                null
            }
        } ?: return null

        return toPath(docId, volumes)
    }

    fun getVolumes(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val primaryDir = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        map["primary"] = primaryDir

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (storageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            for (volume in storageManager.storageVolumes) {
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory?.absolutePath
                } else {
                    null
                } ?: if (volume.isPrimary) primaryDir else null

                if (dir != null) {
                    if (volume.isPrimary) {
                        map["primary"] = dir
                    }
                    volume.uuid?.let { uuid ->
                        map[uuid] = dir
                    }
                }
            }
        }
        return map
    }

    fun toPath(uri: Uri?, context: Context): String? = toPath(uri, getVolumes(context))

    fun toPath(context: Context, uri: Uri?): String? = toPath(uri, getVolumes(context))
}
