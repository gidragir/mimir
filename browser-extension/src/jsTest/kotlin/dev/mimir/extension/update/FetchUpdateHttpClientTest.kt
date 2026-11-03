package dev.mimir.extension.update

import dev.mimir.core.update.FetchUpdateHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchUpdateHttpClientTest {
    private fun response(status: Int, text: String, headers: dynamic, chunks: Array<IntArray>): dynamic {
        val f = js(
            """(function (status, text, headers, chunks) {
                var i = 0;
                var map = new Headers(headers);
                return {
                    status: status,
                    headers: map,
                    text: function () { return Promise.resolve(text); },
                    body: { getReader: function () { return {
                        read: function () {
                            if (i >= chunks.length) return Promise.resolve({ done: true });
                            return Promise.resolve({ done: false, value: new Uint8Array(chunks[i++]) });
                        },
                        cancel: function () {}
                    }; } }
                };
            })"""
        )
        return f(status, text, headers, chunks)
    }

    private fun client(resp: dynamic, seen: MutableList<dynamic> = mutableListOf()) =
        FetchUpdateHttpClient { url, init ->
            seen.add(init)
            if (url.startsWith("boom")) Promise.reject(RuntimeException("network down")) else Promise.resolve(resp)
        }

    @Test
    fun getReturnsStatusLowercaseHeadersAndBodyEvenFor304And429() = runTest {
        for (status in listOf(200, 304, 429)) {
            val resp = response(status, "body", js("({'ETag': 'abc', 'Retry-After': '30'})"), emptyArray())
            val r = client(resp).get("https://x/y", mapOf("Accept" to "a"))
            assertEquals(status, r.statusCode)
            assertEquals("abc", r.headers["etag"])
            assertEquals("30", r.headers["retry-after"])
            assertEquals("body", r.bodyText)
        }
    }

    @Test
    fun getSendsRequestHeaders() = runTest {
        val seen = mutableListOf<dynamic>()
        client(response(200, "", js("({})"), emptyArray()), seen).get("https://x", mapOf("If-None-Match" to "e1"))
        assertEquals("e1", seen.single().headers["If-None-Match"])
    }

    @Test
    fun downloadStreamReportsChunksAndTotal() = runTest {
        val resp = response(200, "", js("({'content-length': '5'})"), arrayOf(intArrayOf(1, 2), intArrayOf(3, 4, 255)))
        val got = mutableListOf<ByteArray>()
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = client(resp).downloadStream("https://x") { chunk, read, total ->
            got += chunk
            progress += read to total
        }
        assertTrue(result.isSuccess)
        assertContentEquals(byteArrayOf(1, 2), got[0])
        assertContentEquals(byteArrayOf(3, 4, -1), got[1])
        assertEquals(listOf(2L to 5L, 5L to 5L), progress)
    }

    @Test
    fun downloadStreamFailsOnHttpErrorAndNetworkError() = runTest {
        val notFound = response(404, "", js("({})"), emptyArray())
        assertTrue(client(notFound).downloadStream("https://x") { _, _, _ -> }.isFailure)
        assertTrue(client(notFound).downloadStream("boom://x") { _, _, _ -> }.isFailure)
    }
}
