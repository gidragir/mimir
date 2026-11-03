---
directory: core/src/commonMain/kotlin/dev/mimir/core/crypto
source:
  kind: git
  blob_sha: 21eb1233b8e656152a7eb6250719148f1fd2abbb
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
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
