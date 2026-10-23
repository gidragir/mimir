package dev.mimir.core

import kotlinx.io.Source

/** Шифрует каждый сегмент пути и содержимое; больше никакой логики. */
class EncryptedRemoteStorage(
    private val inner: RemoteStorage,
    private val cipher: Cipher,
) : ChangeFeedStorage {
    override suspend fun getLatestChangeToken(): String {
        val feed = inner as? ChangeFeedStorage
            ?: throw UnsupportedOperationException("Inner storage does not support ChangeFeedStorage")
        return feed.getLatestChangeToken()
    }

    override suspend fun getChanges(sinceToken: String): RemoteChangesResult {
        val feed = inner as? ChangeFeedStorage
            ?: throw UnsupportedOperationException("Inner storage does not support ChangeFeedStorage")
        val raw = feed.getChanges(sinceToken)
        val decryptedChanges = raw.changes.map { change ->
            change.copy(
                path = decrypt(change.path),
                entry = change.entry?.decrypted(),
            )
        }
        return RemoteChangesResult(decryptedChanges, raw.nextToken)
    }

    override suspend fun list(dir: RemotePath): List<RemoteEntry> =
        inner.list(encrypt(dir)).map { it.decrypted() }

    override suspend fun stat(path: RemotePath): RemoteEntry? =
        inner.stat(encrypt(path))?.decrypted()

    override suspend fun read(path: RemotePath): Source =
        cipher.decrypt(inner.read(encrypt(path)))

    override suspend fun write(path: RemotePath, data: Source): RemoteEntry =
        inner.write(encrypt(path), cipher.encrypt(data)).decrypted()

    override suspend fun delete(path: RemotePath) = inner.delete(encrypt(path))

    private fun encrypt(path: RemotePath) = RemotePath.of(path.segments.map(cipher::encryptName))

    private fun decrypt(path: RemotePath) = RemotePath.of(path.segments.map(cipher::decryptName))

    private fun RemoteEntry.decrypted() = copy(path = decrypt(path))
}
