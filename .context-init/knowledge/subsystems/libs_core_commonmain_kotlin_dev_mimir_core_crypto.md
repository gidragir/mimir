---
directory: libs/core/src/commonMain/kotlin/dev/mimir/core/crypto
source:
  kind: git
  blob_sha: e25c3e7a0ca50441aa0a28264cf432d0b29696c9
  commit_sha: 99aeceb1c6113743f7e25f5d18773752aae3ab95
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 15
  sampled_entries: 15
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Core CommonMain Kotlin Dev Mimir Core Crypto (L1)

Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов)

> **Идентификатор:** `libs_core_commonmain_kotlin_dev_mimir_core_crypto`
> **Теги:** Kotlin (Gradle), libs_core_commonmain_kotlin_dev_mimir_core_crypto

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Aes256.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Base32Hex.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/CanaryVerifier.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Eme.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/EncryptedRemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/EncryptedSecretStore.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/HashingSource.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Pbkdf2.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Poly1305.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Salsa20.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Scrypt.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/SecretBox.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/Sha256.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto/XSalsa20.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal class Aes256(key: ByteArray)
fun encryptBlock(b: ByteArray): ByteArray
fun decryptBlock(b: ByteArray): ByteArray
fun xtime(a: Int): Int = ((a shl 1) xor (if (a and 0x80 != 0) 0x11B else 0)) and 0xFF
fun mul(a: Int, b: Int): Int
fun rotl(v: Int, n: Int) = ((v shl n) or (v shr (8 - n))) and 0xFF
internal object Base32Hex
fun encode(data: ByteArray): String
fun decode(text: String): ByteArray
object CanaryVerifier
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
