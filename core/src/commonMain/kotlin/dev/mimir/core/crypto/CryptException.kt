package dev.mimir.core

/** Ошибка формата или подлинности зашифрованных данных; сообщение не содержит секретов. */
class CryptException(message: String) : Exception(message)
