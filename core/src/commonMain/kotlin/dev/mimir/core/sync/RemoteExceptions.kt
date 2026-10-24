package dev.mimir.core

class RemoteUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RemoteAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
