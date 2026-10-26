package dev.mimir.drive

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.Source

internal actual suspend fun ByteReadChannel.toSource(): Source = readBuffer()
