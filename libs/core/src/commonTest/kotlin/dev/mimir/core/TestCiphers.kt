package dev.mimir.core

/** scrypt дорог, поэтому шифры по конфигурациям golden-векторов создаются один раз на запуск. */
internal object TestCiphers {
    /** Nonce, который отдаёт `nonceSource` общих шифров; тесты содержимого выставляют его сами. */
    var nonce: ByteArray = ByteArray(24)

    val all: List<RcloneCipher> by lazy {
        RcloneVectors.configs.map { RcloneCipher(it.password, it.password2) { nonce } }
    }
}
