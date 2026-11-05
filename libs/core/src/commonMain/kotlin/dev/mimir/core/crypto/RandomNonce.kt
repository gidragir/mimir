package dev.mimir.core

/** 24 байта из криптостойкого генератора платформы. */
internal expect fun randomNonce(): ByteArray
