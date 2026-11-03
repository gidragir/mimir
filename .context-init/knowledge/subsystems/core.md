---
directory: core
source:
  kind: git
  blob_sha: 109bf6ac2615353e6fae78fbfbabda75b38bc705
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

# Подсистема: Core (L1)

Подсистема `core` с ключевыми точками входа: core/package.json

> **Идентификатор:** `core`
> **Теги:** Kotlin (Gradle), core

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `core/CHANGELOG.md` | Исходный код |
| `core/build.gradle.kts` | Исходный код |
| `core/package.json` | ⭐ Точка входа |
| `core/src/androidMain/kotlin/dev/mimir/core/crypto/AndroidKeystoreSecretStore.kt` | Исходный код |
| `core/src/androidMain/kotlin/dev/mimir/core/crypto/RandomNonce.android.kt` | Исходный код |
| `core/src/androidMain/kotlin/dev/mimir/core/fs/AndroidFileObserverVaultWatcher.kt` | Исходный код |
| `core/src/androidMain/kotlin/dev/mimir/core/fs/LastModified.android.kt` | Исходный код |
| `core/src/androidMain/kotlin/dev/mimir/core/fs/SymbolicLink.android.kt` | Исходный код |
| `core/src/jsMain/kotlin/dev/mimir/core/crypto/RandomNonce.js.kt` | Исходный код |
| `core/src/jsMain/kotlin/dev/mimir/core/fs/LastModified.js.kt` | Исходный код |
| `core/src/jsMain/kotlin/dev/mimir/core/update/FetchUpdateHttpClient.kt` | Исходный код |
| `core/src/jvmMain/kotlin/dev/mimir/core/crypto/RandomNonce.jvm.kt` | Исходный код |
| `core/src/jvmMain/kotlin/dev/mimir/core/fs/NioWatchServiceVaultWatcher.kt` | Исходный код |
| `core/src/jvmMain/kotlin/dev/mimir/core/fs/SymbolicLink.jvm.kt` | Исходный код |
| `core/src/wasmJsMain/kotlin/dev/mimir/core/crypto/RandomNonce.wasmJs.kt` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
