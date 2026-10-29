package dev.mimir.android.work

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * Резолвит triggered content URIs в абсолютные пути файловой системы.
 */
fun interface TriggeredPathResolver {
    fun paths(uris: List<Uri>): List<String>

    companion object {
        operator fun invoke(contentResolver: ContentResolver): TriggeredPathResolver =
            ContentResolverTriggeredPathResolver(contentResolver)
    }
}

/**
 * Реализация на основе [ContentResolver], выполняющая один запрос к [MediaStore.Files]
 * с проекцией [_data] и условием [_id IN (...)].
 */
class ContentResolverTriggeredPathResolver(
    private val contentResolver: ContentResolver,
) : TriggeredPathResolver {

    override fun paths(uris: List<Uri>): List<String> {
        if (uris.isEmpty()) return emptyList()

        val ids = uris.mapNotNull { uri ->
            try {
                ContentUris.parseId(uri)
            } catch (_: Exception) {
                uri.lastPathSegment?.toLongOrNull()
            }
        }.distinct()

        if (ids.isEmpty()) return emptyList()

        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val placeholders = ids.joinToString(",") { "?" }
        val selection = "${MediaStore.MediaColumns._ID} IN ($placeholders)"
        val selectionArgs = ids.map { it.toString() }.toTypedArray()

        val results = mutableListOf<String>()
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIndex != -1) {
                    while (cursor.moveToNext()) {
                        cursor.getString(dataIndex)?.let { results.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            // Игнорируем ошибки доступа к ContentResolver
        }
        return results
    }
}
