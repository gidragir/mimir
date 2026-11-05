package dev.mimir.core

/** Ошибка формата или подлинности зашифрованных данных; сообщение не содержит секретов. */
class CryptException(message: String, cause: Throwable? = null) : Exception(message, cause)
