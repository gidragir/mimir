package dev.mimir.core.sync

import dev.mimir.core.LocalEntry
import dev.mimir.core.RemoteEntry
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.Source
import kotlin.time.Clock
import kotlin.time.Instant

data class ConflictDetails(
    val path: String,
    val localEntry: LocalEntry,
    val remoteEntry: RemoteEntry,
    val baseRecord: FileSyncRecord?,
)

data class ConflictOutcome(
    val mainPath: String,
    val conflictPath: String,
    val conflictSource: Source,
)

interface ConflictResolver {
    /**
     * Куда положить версии при настоящем конфликте. [localSource] — локальное содержимое основного файла;
     * удалённое до решения не скачивается (спека sync-io §4).
     */
    suspend fun resolveConflict(
        details: ConflictDetails,
        localSource: Source,
    ): ConflictOutcome

    companion object {
        fun generateConflictPath(path: String, timestamp: Instant): String {
            val normalized = path.replace('\\', '/')
            val lastSlash = normalized.lastIndexOf('/')
            val dir = if (lastSlash >= 0) normalized.substring(0, lastSlash) else ""
            val filename = if (lastSlash >= 0) normalized.substring(lastSlash + 1) else normalized

            val lastDot = filename.lastIndexOf('.')
            val (baseName, ext) = if (lastDot > 0) {
                filename.substring(0, lastDot) to filename.substring(lastDot)
            } else {
                filename to ""
            }

            val dt = timestamp.toLocalDateTime(TimeZone.UTC)
            val y = dt.year.toString().padStart(4, '0')
            val m = (dt.month.ordinal + 1).toString().padStart(2, '0')
            val d = dt.day.toString().padStart(2, '0')
            val h = dt.hour.toString().padStart(2, '0')
            val min = dt.minute.toString().padStart(2, '0')
            val s = dt.second.toString().padStart(2, '0')
            val formattedTime = "$y-$m-$d-$h$min$s"

            val conflictedFilename = "$baseName (conflicted copy $formattedTime)$ext"
            return if (dir.isNotEmpty()) "$dir/$conflictedFilename" else conflictedFilename
        }
    }
}

class DefaultConflictResolver(
    private val clock: Clock = Clock.System,
) : ConflictResolver {

    override suspend fun resolveConflict(
        details: ConflictDetails,
        localSource: Source,
    ): ConflictOutcome {
        val conflictPath = ConflictResolver.generateConflictPath(details.path, clock.now())
        return ConflictOutcome(
            mainPath = details.path,
            conflictPath = conflictPath,
            conflictSource = localSource,
        )
    }
}
