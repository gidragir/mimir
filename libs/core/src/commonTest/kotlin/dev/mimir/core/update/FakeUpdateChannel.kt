package dev.mimir.core.update

/** Фейк [UpdateChannel]: отдаёт настроенный результат (или бросает [throwable]) и считает вызовы. */
class FakeUpdateChannel(
    var result: UpdateCheckResult,
    var throwable: Throwable? = null,
) : UpdateChannel {
    override val name: String = "fake"
    var calls: Int = 0
        private set

    override suspend fun checkForUpdates(currentVersion: SemVer, targetPlatform: TargetPlatform): UpdateCheckResult {
        calls++
        throwable?.let { throw it }
        return result
    }
}
