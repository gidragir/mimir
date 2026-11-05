---
directory: libs/core/src/commonMain/kotlin/dev/mimir/core/crypto
source:
  kind: git
  blob_sha: 0482f8f9762bb7ef35527e84872a8592c97fb537
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
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

# Подсистема: Libs Core Src CommonMain Kotlin Dev Mimir Core Crypto (L1)

Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов)

> **Идентификатор:** `libs_core_src_commonmain_kotlin_dev_mimir_core_crypto`
> **Теги:** Kotlin (Gradle), libs_core_src_commonmain_kotlin_dev_mimir_core_crypto

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
class EncryptedRemoteStorage(...) : ChangeFeedStorage
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
