package dev.mimir.drive

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.asSource
import kotlinx.io.Source
import kotlinx.io.buffered

internal actual suspend fun ByteReadChannel.toSource(): Source = asSource().buffered()
