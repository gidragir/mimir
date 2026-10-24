package dev.mimir.daemon

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

data class ConflictFileEntry(val originalPath: String, val conflictPath: String, val createdAt: Instant)

/** Конфликтные копии, которые создаёт движок: `Имя (conflicted copy YYYY-MM-DD-HHMMSS).ext`. */
internal object ConflictFiles {
    private val pattern = Regex("""^(.*) \(conflicted copy (\d{4})-(\d{2})-(\d{2})-(\d{2})(\d{2})(\d{2})\)(\.[^./]*)?$""")

    fun parse(conflictPath: String): ConflictFileEntry? {
        val slash = conflictPath.lastIndexOf('/')
        val dir = conflictPath.substring(0, slash + 1)
        val match = pattern.matchEntire(conflictPath.substring(slash + 1)) ?: return null
        val (base, y, mo, d, h, mi, s) = match.destructured
        val createdAt = runCatching {
            LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).toInstant(TimeZone.UTC)
        }.getOrNull() ?: return null
        return ConflictFileEntry(dir + base + match.groupValues[8], conflictPath, createdAt)
    }

    fun find(paths: List<String>): List<ConflictFileEntry> = paths.mapNotNull(::parse).sortedBy { it.conflictPath }
}
