---
directory: libs/runtime/src/commonMain/kotlin/dev/mimir/runtime
source:
  kind: git
  blob_sha: 986c270e71d9f0006071b56199b92fc9b09b89fa
  commit_sha: eb1dfb29262521892d6c4ce3c3bda7207dfd6e87
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

# Подсистема: Runtime CommonMain Kotlin Dev Mimir Runtime (L1)

Модуль и компоненты директории `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime` (15 файлов)

> **Идентификатор:** `libs_runtime_commonmain_kotlin_dev_mimir_runtime`
> **Теги:** Kotlin (Gradle), libs_runtime_commonmain_kotlin_dev_mimir_runtime

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/AccountStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/AtomicFiles.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/Canary.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/ConflictFiles.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/ConflictTracker.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/EngineAssembler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/FileTokenStorage.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonRuntimeSettingsStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonSyncStateStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/MimirRuntime.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/SyncScheduler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/DeviceCodeLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/LoginProvider.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal class AccountStore(dataDir: Path)
fun email(): String? = read()?.email
suspend fun method(): DaemonLoginMethod?
fun save(email: String?, method: DaemonLoginMethod)
fun delete()
fun writeFileAtomically(target: Path, content: String, ownerOnly: Boolean = false)
class InvalidPasswordException(message: String) : IllegalArgumentException(message)
suspend fun verifyOrInitCanary(remote: RemoteStorage, cipher: Cipher, configDir: Path)
class SyncNotConfiguredException(message: String) : IllegalStateException(message)
typealias ConflictFileEntry = dev.mimir.core.ConflictFileEntry
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
