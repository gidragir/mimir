package dev.mimir.core

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source

/** Пропускает байты upstream без изменений и считает их SHA-256 по ходу чтения. */
class HashingSource(private val upstream: RawSource) : RawSource {
    private val hasher = Sha256.Hasher()
    private var exhausted = false
    private var hex: String? = null

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (exhausted) return -1
        val before = sink.size
        val read = upstream.readAtMostTo(sink, byteCount)
        if (read == -1L) {
            exhausted = true
            return -1
        }
        // Новые байты — хвост sink длиной read; копируем их, не трогая sink.
        val chunk = sink.copyTo(read, before)
        hasher.update(chunk)
        return read
    }

    /** SHA-256 всего прочитанного; допустим только после исчерпания upstream. */
    fun hashHex(): String {
        check(exhausted) { "Source is not exhausted yet" }
        return hex ?: hasher.digestHex().also { hex = it }
    }

    override fun close() = upstream.close()

    private fun Buffer.copyTo(count: Long, startIndex: Long): ByteArray {
        val bytes = ByteArray(count.toInt())
        val copy = Buffer()
        this.copyTo(copy, startIndex, startIndex + count)
        copy.readAtMostTo(bytes)
        return bytes
    }
}

/** Потоковый SHA-256 источника блоками по 64 КиБ; источник закрывается. Не приостанавливается. */
fun Source.sha256Hex(): String = use { source ->
    val hasher = Sha256.Hasher()
    val block = ByteArray(HASH_BLOCK_SIZE)
    while (true) {
        val n = source.readAtMostTo(block, 0, block.size)
        if (n == -1) break
        hasher.update(block, 0, n)
    }
    hasher.digestHex()
}

private const val HASH_BLOCK_SIZE = 64 * 1024
