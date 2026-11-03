---
directory: core/src/commonMain/kotlin/dev/mimir/core/crypto
source:
  kind: git
  blob_sha: 3d12d3d4869ace6b3ea8340ecf0efcf41b283052
  commit_sha: 0ca8c0bfc25f095f72fe8f8aff5beb38597b19dd
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

Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов)

> **Идентификатор:** `core_commonmain_kotlin_dev_mimir_core_crypto`
> **Теги:** Kotlin (Gradle), core_commonmain_kotlin_dev_mimir_core_crypto

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Aes256.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Base32Hex.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/CanaryVerifier.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Eme.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/EncryptedRemoteStorage.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/EncryptedSecretStore.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/HashingSource.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Pbkdf2.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Poly1305.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/RcloneCipher.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Salsa20.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Scrypt.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/SecretBox.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/Sha256.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/crypto/XSalsa20.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class EncryptedRemoteStorage(...) : ChangeFeedStorage
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
