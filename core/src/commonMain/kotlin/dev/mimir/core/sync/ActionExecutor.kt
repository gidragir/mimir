package dev.mimir.core.sync

import dev.mimir.core.CryptException
import dev.mimir.core.HashingSource
import dev.mimir.core.LocalEntry
import dev.mimir.core.LocalVault
import dev.mimir.core.RemoteAuthException
import dev.mimir.core.RemoteEntry
import dev.mimir.core.RemotePath
import dev.mimir.core.RemoteStorage
import dev.mimir.core.RemoteUnavailableException
import dev.mimir.core.SyncItemAction
import dev.mimir.core.SyncProblem
import dev.mimir.core.SyncProblemKind
import dev.mimir.core.VaultFileId
import dev.mimir.core.sha256Hex
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlin.coroutines.cancellation.CancellationException

/**
 * Исход одного действия: что записать в индекс и что показать в `completed`.
 * [completedAs] == null — действие обработано, но ничего не перенесено (совпавший `Reconcile`,
 * отложенный `DeleteLocal`).
 */
internal class ActionResult(
    val completedAs: SyncItemAction?,
    val put: List<FileSyncRecord> = emptyList(),
    val remove: String? = null,
) {
    val changesIndex: Boolean get() = put.isNotEmpty() || remove != null

    fun applyTo(index: SyncIndex): SyncIndex =
        put.fold(remove?.let(index::withoutRecord) ?: index) { acc, record -> acc.withRecord(record) }
}

/**
 * Эффекты одного действия плана. Данные идут потоком: всё, что записано или отправлено,
 * проходит через [HashingSource], и хэш записи индекса берётся из него (спека sync-io §4–§5).
 * Ошибки локального vault помечаются [LocalIoFailure] — и тогда, когда они случаются внутри
 * чтения потока другой стороной.
 */
internal class ActionExecutor(
    private val localVault: LocalVault,
    private val remoteStorage: RemoteStorage,
    private val conflictResolver: ConflictResolver,
) {

    /** SHA-256 локального файла потоком; ошибки — [LocalIoFailure] с [SyncProblemKind.ReadFailed]. */
    suspend fun localHash(entry: LocalEntry): String =
        localIo(SyncProblemKind.ReadFailed) { localVault.read(entry.id).sha256Hex() }

    /** [index] нужен только `DeleteLocal`: путь удалённого файла для проверки `stat`. */
    suspend fun execute(action: SyncAction, index: SyncIndex): ActionResult = when (action) {
        is SyncAction.Upload ->
            ActionResult(SyncItemAction.Upload, put = listOf(upload(action.path, action.localEntry, action.remotePath)))

        is SyncAction.Download ->
            ActionResult(SyncItemAction.Download, put = listOf(download(action.path, action.remoteEntry)))

        is SyncAction.Reconcile -> reconcile(action)

        is SyncAction.DeleteRemote -> {
            remoteStorage.delete(action.remotePath)
            ActionResult(SyncItemAction.DeleteRemote, remove = action.path)
        }

        is SyncAction.DeleteLocal ->
            if (remoteStillExists(action, index)) {
                // Список удалённых файлов отстал: файл на месте. Локальный файл и запись индекса
                // не трогаем — следующий прогон проверит снова. Для прогресса это «обработано»
                // (done растёт), но не «выполнено» (в completed не попадает) и не проблема.
                ActionResult(completedAs = null)
            } else {
                localIo(SyncProblemKind.WriteFailed) { localVault.delete(action.localId) }
                ActionResult(SyncItemAction.DeleteLocal, remove = action.path)
            }

        is SyncAction.UpdateMetadata -> ActionResult(completedAs = null, put = listOf(action.record))

        is SyncAction.NoOp -> ActionResult(completedAs = null)
    }

    private suspend fun upload(path: String, localEntry: LocalEntry, remotePath: RemotePath): FileSyncRecord {
        val hashing = HashingSource(localSource(localEntry.id))
        val remoteEntry = hashing.buffered().use { remoteStorage.write(remotePath, it) }
        return record(path, localEntry, hashing.hashHex(), remoteEntry)
    }

    private suspend fun download(localPath: String, remoteEntry: RemoteEntry): FileSyncRecord {
        val hashing = HashingSource(remoteSource(remoteEntry.path))
        val localEntry = hashing.buffered().use { localIo(SyncProblemKind.WriteFailed) { localVault.write(localPath, it) } }
        return record(localPath, localEntry, hashing.hashHex(), remoteEntry)
    }

    /**
     * Спека §4: удалённый файл скачивается потоком только ради хэша, ничего не записывая.
     * Совпало с локальным — только запись индекса; удалённый равен базе — `Upload`; иначе конфликт.
     */
    private suspend fun reconcile(action: SyncAction.Reconcile): ActionResult {
        val remoteHash = remoteStorage.read(action.remoteEntry.path).sha256Hex()
        val localHash = localHash(action.localEntry)
        if (remoteHash == localHash) {
            return ActionResult(
                completedAs = null,
                put = listOf(record(action.path, action.localEntry, localHash, action.remoteEntry)),
            )
        }
        if (action.baseRecord != null && remoteHash == action.baseRecord.baseHash) {
            return ActionResult(
                SyncItemAction.Upload,
                put = listOf(upload(action.path, action.localEntry, action.remoteEntry.path)),
            )
        }
        return conflict(action)
    }

    /**
     * Порядок важен: (а) локальная копия конфликта из локального содержимого, (б) второе скачивание
     * удалённого файла в основной путь, (в) отправка копии. Если копию записать не удалось, локальная
     * правка остаётся на месте (следующий прогон снова увидит конфликт); упадёт (б) или (в) — в худшем
     * случае лишняя копия, но не потеря данных.
     */
    private suspend fun conflict(action: SyncAction.Reconcile): ActionResult {
        val details = ConflictDetails(action.path, action.localEntry, action.remoteEntry, action.baseRecord)
        val (outcome, copyEntry) = localSource(action.localEntry.id).buffered().use { local ->
            val outcome = conflictResolver.resolveConflict(details, local)
            val copy = outcome.conflictSource.use { source ->
                localIo(SyncProblemKind.WriteFailed) { localVault.write(outcome.conflictPath, source) }
            }
            outcome to copy
        }
        val mainRecord = download(outcome.mainPath, action.remoteEntry)
        val copyRecord = upload(outcome.conflictPath, copyEntry, RemotePath(outcome.conflictPath))
        return ActionResult(SyncItemAction.Conflict, put = listOf(mainRecord, copyRecord))
    }

    /**
     * Перед удалением локального файла заново спрашивает удалённое хранилище ([RemoteStorage.stat]).
     * Сетевые/авторизационные ошибки прерывают прогон; любая другая ошибка проверки —
     * [DeleteVerificationFailure] (файл не удаляется, пользователь видит проблему).
     */
    private suspend fun remoteStillExists(action: SyncAction.DeleteLocal, index: SyncIndex): Boolean {
        val remotePath = index.find(action.path)?.remotePath ?: RemotePath(action.path)
        return try {
            remoteStorage.stat(remotePath) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteUnavailableException) {
            throw e
        } catch (e: RemoteAuthException) {
            throw e
        } catch (e: Exception) {
            throw DeleteVerificationFailure(e)
        }
    }

    /** Локальный файл как поток, чьи ошибки чтения — [LocalIoFailure], кто бы его ни читал. */
    private suspend fun localSource(id: VaultFileId): RawSource {
        val source = localIo(SyncProblemKind.ReadFailed) { localVault.read(id) }
        return FailureTaggingSource(source) { LocalIoFailure(SyncProblemKind.ReadFailed, it) }
    }

    /** Удалённый файл как поток: ошибки его чтения (например, расшифровки) не выдаются за ошибки записи vault. */
    private suspend fun remoteSource(path: RemotePath): RawSource =
        FailureTaggingSource(remoteStorage.read(path)) { RemoteStreamFailure(it) }

    private fun record(path: String, local: LocalEntry, hash: String, remote: RemoteEntry) = FileSyncRecord(
        relativePath = path,
        localId = local.id,
        baseHash = hash,
        localSize = local.size,
        localModifiedAt = local.modifiedAt,
        remotePath = remote.path,
        remoteRevision = remote.revision,
        remoteModifiedAt = remote.modifiedAt,
        remoteSize = remote.size,
    )
}

/** Ошибка локального vault с видом проблемы для пользователя. */
internal class LocalIoFailure(val kind: SyncProblemKind, override val cause: Exception) : Exception(cause.message, cause)

internal class DeleteVerificationFailure(override val cause: Exception) :
    Exception("Не удалось проверить удалённый файл перед удалением: ${cause.message ?: cause::class.simpleName}", cause)

/** Ошибка чтения удалённого потока, случившаяся внутри записи vault; [localIo] отдаёт исходную. */
private class RemoteStreamFailure(override val cause: Exception) : Exception(cause.message, cause)

private class FailureTaggingSource(
    private val upstream: RawSource,
    private val tag: (Exception) -> Exception,
) : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        try {
            upstream.readAtMostTo(sink, byteCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw tag(e)
        }

    override fun close() = upstream.close()
}

/**
 * Ошибки vault — в [LocalIoFailure] вида [kind]. Уже помеченные ошибки проходят как есть:
 * [LocalIoFailure] (локальное чтение внутри записи) и исходная ошибка удалённого потока.
 */
private inline fun <T> localIo(kind: SyncProblemKind, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalIoFailure) {
        throw e
    } catch (e: RemoteStreamFailure) {
        throw e.cause
    } catch (e: Exception) {
        throw LocalIoFailure(kind, e)
    }

internal fun problemOf(path: String, action: SyncItemAction, error: Throwable): SyncProblem {
    val (kind, origin) = when (error) {
        is LocalIoFailure -> error.kind to error.cause
        is DeleteVerificationFailure -> SyncProblemKind.Unknown to error
        is CryptException -> SyncProblemKind.DecryptFailed to error
        else -> SyncProblemKind.Unknown to error
    }
    val detail = origin.message ?: origin::class.simpleName ?: "Unknown error"
    return SyncProblem(path, action, kind, detail)
}
