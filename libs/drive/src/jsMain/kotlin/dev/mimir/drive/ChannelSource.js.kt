package dev.mimir.drive

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.Source

/**
 * JS/wasm: блокирующего чтения из сети нет, поэтому тело читается целиком. Это допустимо: скачивание файлов Drive идёт
 * только в демоне (JVM) и на Android, а JS-клиенты (Obsidian, расширение, web-ui) работают через `:client` по HTTP.
 */
internal actual suspend fun ByteReadChannel.toSource(): Source = readBuffer()
