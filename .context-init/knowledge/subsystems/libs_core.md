---
directory: libs/core
source:
  kind: git
  blob_sha: 9974a32848a3f78d589730200c029947b95bafea
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

# Подсистема: Libs Core (L1)

Модуль и компоненты директории `libs/core` (15 файлов)

> **Идентификатор:** `libs_core`
> **Теги:** Kotlin (Gradle), libs_core

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/core/build.gradle.kts` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/crypto/AndroidKeystoreSecretStore.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/crypto/RandomNonce.android.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/fs/AndroidFileObserverVaultWatcher.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/fs/LastModified.android.kt` | Исходный код |
| `libs/core/src/androidMain/kotlin/dev/mimir/core/fs/SymbolicLink.android.kt` | Исходный код |
| `libs/core/src/jsMain/kotlin/dev/mimir/core/crypto/RandomNonce.js.kt` | Исходный код |
| `libs/core/src/jsMain/kotlin/dev/mimir/core/fs/LastModified.js.kt` | Исходный код |
| `libs/core/src/jsMain/kotlin/dev/mimir/core/update/FetchUpdateHttpClient.kt` | Исходный код |
| `libs/core/src/jvmMain/kotlin/dev/mimir/core/crypto/RandomNonce.jvm.kt` | Исходный код |
| `libs/core/src/jvmMain/kotlin/dev/mimir/core/fs/LastModified.jvm.kt` | Исходный код |
| `libs/core/src/jvmMain/kotlin/dev/mimir/core/fs/NioWatchServiceVaultWatcher.kt` | Исходный код |
| `libs/core/src/jvmMain/kotlin/dev/mimir/core/fs/SymbolicLink.jvm.kt` | Исходный код |
| `libs/core/src/wasmJsMain/kotlin/dev/mimir/core/crypto/RandomNonce.wasmJs.kt` | Исходный код |
| `libs/core/src/wasmJsMain/kotlin/dev/mimir/core/fs/LastModified.wasmJs.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class AndroidFileObserverVaultWatcher( private val root: File, rules: SyncRules = SyncRules(), private val debounce: Duration = 1.seconds, echoTtl: Duration = 2.seconds, private val clock: Clock = Clock.System, ) : VaultWatcher
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
