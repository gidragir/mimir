package dev.mimir.core

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source

/** Пропускает байты upstream без изменений и считает их SHA-256 по ходу чтения. */
class HashingSource(private val upstream: RawSource) : RawSource {
    private val hasher = Sha256.Hasher()
    private val copyBuffer = Buffer()
    private val scratch = ByteArray(8192)
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
        // Новые байты — хвост sink длиной read; копируем их во временный буфер, не трогая sink.
        sink.copyTo(copyBuffer, before, before + read)
        while (!copyBuffer.exhausted()) {
            val n = copyBuffer.readAtMostTo(scratch, 0, scratch.size)
            if (n <= 0) break
            hasher.update(scratch, 0, n)
        }
        return read
    }

    /** SHA-256 всего прочитанного; допустим только после исчерпания upstream. */
    fun hashHex(): String {
        check(exhausted) { "Source is not exhausted yet" }
        return hex ?: hasher.digestHex().also { hex = it }
    }

    override fun close() = upstream.close()
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
