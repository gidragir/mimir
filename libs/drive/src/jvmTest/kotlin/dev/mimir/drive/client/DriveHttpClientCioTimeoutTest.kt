package dev.mimir.drive.client

import dev.mimir.drive.auth.AccessTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * У движка CIO свой `requestTimeout` (15 с по умолчанию) на весь вызов вместе с чтением тела; он действует,
 * пока на запросе нет плагина `HttpTimeout`. Файл Drive, который передаётся дольше, обрывался на каждой попытке.
 * Здесь тот же движок с таймаутом 80 мс и тело, которое идёт ~200 мс.
 */
class DriveHttpClientCioTimeoutTest {
    private object FixedToken : AccessTokenSource {
        override suspend fun token() = "t"
        override suspend fun refresh() = "t"
        override suspend fun hasCredentials() = true
    }

    @Test
    fun slowLargeDownloadOutlivesTheCioEngineRequestTimeout() = runBlocking<Unit> {
        val payload = ByteArray(8 * 4096) { it.toByte() }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serving = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().orEmpty().isNotEmpty()) Unit
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    payload.toList().chunked(4096).forEach { part ->
                        out.write(part.toByteArray())
                        out.flush()
                        Thread.sleep(25)
                    }
                }
            }
            val received = HttpClient(CIO) { engine { requestTimeout = 80 } }.use { http ->
                val drive = DriveHttpClient(http, FixedToken)
                drive.executeStreaming(
                    request = { token ->
                        drive.httpClient.prepareGet("http://127.0.0.1:${server.localPort}/file") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    },
                ) { response -> response.bodyAsChannel().toByteArray() }
            }
            serving.join(5_000)
            assertContentEquals(payload, received)
        }
    }
}
