---
directory: libs/runtime/src/commonMain/kotlin/dev/mimir/runtime
source:
  kind: git
  blob_sha: 3cbdedf501821f1aa712e0ce0d2cb89b87e0a4f2
  commit_sha: bca5a71aa25e84ea197c3bf1e2540a4de155de19
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
