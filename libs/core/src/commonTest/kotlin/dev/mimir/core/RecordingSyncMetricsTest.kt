package dev.mimir.core

import dev.mimir.core.testing.TestClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RecordingSyncMetricsTest {
    @Test
    fun aggregatesByOpAndCountsRateLimitsSeparately() = runTest {
        val clock = TestClock()
        val m = RecordingSyncMetrics(clock)
        m.runStarted()
        listOf(100L, 200L, 300L, 400L).forEach {
            m.onRequest(RequestSample("upload-multipart", true, it, 200, null, 1, 0, false))
        }
        m.onRequest(RequestSample("list-q", false, 50, 403, "userRateLimitExceeded", 1, 0, false))
        m.onRequest(RequestSample("list-q", false, 60, 403, "rateLimitExceeded", 2, 1000, false))
        m.onRequest(RequestSample("list-q", false, 70, 429, null, 3, 2000, false))
        m.onRequest(RequestSample("get-media", false, 10, null, null, 1, 0, true))
        m.onLockWait("folderCreation", 150)
        clock.advance(10.seconds)
        val s = assertNotNull(m.runFinished("ok"))
        assertEquals(8, s.requests)
        assertEquals(4, s.writes)
        assertEquals(0.4, s.writesPerSecond, 1e-9)
        assertEquals(OpStats(4, 200, 400), s.byOp["upload-multipart"])
        assertEquals(1, s.rateLimitedUser)
        assertEquals(1, s.rateLimitedProject)
        assertEquals(1, s.tooManyRequests)
        assertEquals(1, s.networkErrors)
        assertEquals(3000, s.retrySleepMs)
        assertEquals(150L, s.lockWaitMs["folderCreation"])
        assertEquals(
            "sync-run outcome=ok dur=10.00s req=8 writes=4 wps=0.40 403u=1 403p=1 429=1 5xx=0 net=1 sleep=3000 " +
                "get-media=1/p50=10/p95=10 list-q=3/p50=60/p95=70 upload-multipart=4/p50=200/p95=400",
            s.toLogLine(),
        )
        assertNull(m.runFinished("ok")?.takeIf { it.requests > 0 }) // накопленное сброшено
    }
}
