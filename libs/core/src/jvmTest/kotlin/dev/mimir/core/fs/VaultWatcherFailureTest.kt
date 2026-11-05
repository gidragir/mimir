package dev.mimir.core.fs

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class VaultWatcherFailureTest {
    @Test
    fun ioFailureReachesCollectorSoSchedulerFallsBackToPolling() {
        val root = Files.createTempDirectory("mimir-watch-gone")
        Files.delete(root)
        assertFailsWith<IOException> {
            runBlocking { NioWatchServiceVaultWatcher(root).start().collect { } }
        }
    }
}
