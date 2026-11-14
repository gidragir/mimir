package dev.mimir.android.work

internal class FakeSyncRunner(
    private val onSync: () -> Unit = {},
    var shouldThrow: Boolean = false,
) : SyncRunner {
    var callCount = 0

    override suspend fun syncOnce(): Boolean {
        callCount++
        onSync()
        if (shouldThrow) throw IllegalStateException("sync error")
        return true
    }
}
