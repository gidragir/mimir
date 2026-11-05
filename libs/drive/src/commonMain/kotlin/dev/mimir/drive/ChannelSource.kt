package dev.mimir.drive

import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.Source

internal expect suspend fun ByteReadChannel.toSource(): Source
