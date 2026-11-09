package dev.mimir.obsidian.diag

import dev.mimir.core.RcloneCipher
import dev.mimir.obsidian.App
import dev.mimir.obsidian.Notice
import dev.mimir.obsidian.Platform
import dev.mimir.obsidian.requestUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.json
import kotlin.time.TimeSource
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

private const val MIB = 1024 * 1024
private const val DIAG_FILE = "mimir-diag.bin"
private const val REPORT_FILE = "mimir-diagnostics.md"
private const val STREAM_MIB = 300

private fun bytes(n: Int): org.khronos.webgl.ArrayBuffer = Int8Array(n).buffer

private suspend inline fun check(name: String, block: () -> String): String =
    try {
        "$name: OK ${block()}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "$name: FAIL ${e.message ?: e.toString()}"
    }

/** Четыре проверки платформы: appendBinary, поток по getResourcePath, requestUrl+Range, scrypt. */
suspend fun runDiagnostics(app: App): List<String> {
    val a = app.vault.adapter
    val platform = "platform: mobile=${Platform.isMobile} ios=${Platform.isIosApp} android=${Platform.isAndroidApp} desktop=${Platform.isDesktopApp}"
    val r1 = check("1 appendBinary") {
        try {
            a.writeBinary(DIAG_FILE, bytes(MIB)).await()
            a.appendBinary(DIAG_FILE, bytes(3 * MIB)).await()
            val st: dynamic = a.stat(DIAG_FILE).await()
            val size = st.size as? Number
            check(size?.toLong() == 4L * MIB) { "size=$size, expected ${4 * MIB}" }
            "size=$size"
        } finally {
            runCatching { a.remove(DIAG_FILE).await() }
        }
    }
    val r2 = check("2 stream ${STREAM_MIB}MB") {
        val big = "mimir-diag-big.bin"
        try {
            a.writeBinary(big, bytes(8 * MIB)).await()
            var written = 8
            while (written < STREAM_MIB) {
                val n = minOf(8, STREAM_MIB - written)
                a.appendBinary(big, bytes(n * MIB)).await()
                written += n
            }
            val response = kotlinx.browser.window.fetch(a.getResourcePath(big)).await()
            val reader = response.body!!.getReader().unsafeCast<dynamic>()
            var total = 0.0
            while (true) {
                val chunk = reader.read().unsafeCast<kotlin.js.Promise<dynamic>>().await()
                if (chunk.done == true) break
                total += (chunk.value as Uint8Array).length.toDouble()
            }
            check(total.toLong() == STREAM_MIB.toLong() * MIB) { "read $total bytes" }
            val peak = js("(typeof performance !== 'undefined' && performance.memory) ? performance.memory.usedJSHeapSize : -1") as Number
            "bytes=${total.toLong()} peakHeap=${peak}"
        } finally {
            runCatching { a.remove(big).await() }
        }
    }
    val r3 = check("3 requestUrl+Range") {
        val g = requestUrl(
            json(
                "url" to "https://www.googleapis.com/drive/v3/about?fields=kind",
                "headers" to json("Range" to "bytes=0-9"),
                "throw" to false,
            ),
        ).await()
        val h = requestUrl(
            json(
                "url" to "https://httpbin.org/range/1024",
                "headers" to json("Range" to "bytes=0-99"),
                "throw" to false,
            ),
        ).await()
        val len = Uint8Array(h.arrayBuffer).length
        check(h.status == 206 && len == 100) { "httpbin status=${h.status} len=$len" }
        "google=${g.status} httpbin=${h.status}/$len"
    }
    val r4 = check("4 RcloneCipher.create") {
        val t = TimeSource.Monotonic.markNow()
        RcloneCipher.create("diagnostics")
        "${t.elapsedNow().inWholeMilliseconds} ms"
    }
    return listOf(platform, r1, r2, r3, r4)
}

fun showDiagnostics(app: App, lines: List<String>) {
    val text = lines.joinToString("\n")
    Notice(text, 0)
    app.vault.adapter.write(REPORT_FILE, "# Mimir diagnostics\n\n" + lines.joinToString("\n") { "- $it" } + "\n")
}
