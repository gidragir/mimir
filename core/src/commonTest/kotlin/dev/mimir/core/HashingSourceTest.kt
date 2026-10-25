package dev.mimir.core

import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HashingSourceTest {
    private fun sourceOf(bytes: ByteArray) = Buffer().also { it.write(bytes) }

    @Test
    fun passesBytesThroughAndHashes() {
        val data = ByteArray(200_000) { (it * 13 + 5).toByte() }
        val hashing = HashingSource(sourceOf(data))
        val out = hashing.buffered().readByteArray()
        assertContentEquals(data, out)
        assertEquals(Sha256.digest(data).toHex(), hashing.hashHex())
    }

    @Test
    fun hashHexBeforeExhaustionFails() {
        val hashing = HashingSource(sourceOf(ByteArray(100_000) { 1 }))
        val sink = Buffer()
        hashing.readAtMostTo(sink, 10)
        assertFailsWith<IllegalStateException> { hashing.hashHex() }
    }

    @Test
    fun emptySourceHashesToEmptyDigest() {
        val hashing = HashingSource(sourceOf(ByteArray(0)))
        assertEquals(0, hashing.buffered().readByteArray().size)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hashing.hashHex())
    }

    @Test
    fun hashHexCanBeReadTwice() {
        val hashing = HashingSource(sourceOf("abc".encodeToByteArray()))
        hashing.buffered().readByteArray()
        assertEquals(hashing.hashHex(), hashing.hashHex())
    }

    @Test
    fun closeClosesUpstream() {
        var closed = false
        val upstream = object : kotlinx.io.RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1
            override fun close() { closed = true }
        }
        HashingSource(upstream).close()
        assertEquals(true, closed)
    }

    @Test
    fun sha256HexStreamsAndClosesSource() {
        for (length in listOf(0, 1, 65_535, 65_536, 65_537, 300_000)) {
            val data = ByteArray(length) { (it * 3).toByte() }
            var closed = false
            val upstream = object : kotlinx.io.RawSource {
                private val inner = sourceOf(data)
                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = inner.readAtMostTo(sink, byteCount)
                override fun close() { closed = true }
            }
            assertEquals(Sha256.digest(data).toHex(), upstream.buffered().sha256Hex(), "length=$length")
            assertEquals(true, closed, "length=$length")
        }
    }
}
