package dev.mimir.core

class RemoteUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RemoteAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Синхронизация остановлена до каких-либо изменений файлов, потому что продолжать небезопасно
 * (например, удалённый список пуст при непустом индексе). Сообщение показывается пользователю.
 */
class SyncAbortedException(message: String) : Exception(message)
