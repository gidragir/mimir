package dev.mimir.core.fs

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Запоминает пути, записанные самим движком синхронизации, чтобы их события не вернулись в облако. */
@OptIn(ExperimentalAtomicApi::class)
class EchoFilter(private val ttl: Duration, private val clock: Clock) {
    private val marks = AtomicReference<Map<String, Instant>>(emptyMap())

    fun mark(relativePath: String) {
        val now = clock.now()
        while (true) {
            val old = marks.load()
            val updated = old.filterValues { now - it < ttl } + (relativePath to now)
            if (marks.compareAndSet(old, updated)) return
        }
    }

    fun isRecent(relativePath: String): Boolean {
        val markedAt = marks.load()[relativePath] ?: return false
        return clock.now() - markedAt < ttl
    }
}
