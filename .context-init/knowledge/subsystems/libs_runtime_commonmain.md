---
directory: libs/runtime/src/commonMain/kotlin/dev/mimir/runtime
source:
  kind: git
  blob_sha: 132ea75d447636ffe18c43f6c9387febea825eaf
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
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

# Подсистема: Runtime CommonMain (L1)

Модуль и компоненты директории `libs/runtime/src/commonMain` (15 файлов)

> **Идентификатор:** `libs_runtime_commonmain`
> **Теги:** Kotlin (Gradle), libs_runtime_commonmain

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/Canary.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/ConflictTracker.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/EngineAssembler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/JsonSyncStateStore.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/MimirRuntime.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/SyncScheduler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/BrowserLoginReceiver.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/login/DeviceCodeLoginProvider.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/ApkInstallApplier.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/KtorUpdateHttpClient.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/RuntimeUpdates.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateFacade.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateScheduler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/VerifiedFileDownloader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class InvalidPasswordException(message: String) : IllegalArgumentException(message)
suspend fun verifyOrInitCanary(remote: RemoteStorage, cipher: Cipher, configDir: Path)
class SyncNotConfiguredException(message: String) : IllegalStateException(message)
internal class ConflictTracker( private val scope: CoroutineScope, private val watcherFactory: VaultWatcherFactory, private val localVaultFactory: (Path) -> LocalVault, private val scanInterval: Duration, private val vaultPath: () -> String?, )
fun watch()
fun stop()
suspend fun refresh(): List<ConflictFileEntry> = refreshMutex.withLock
suspend fun resolve(originalPath: String, conflictPath: String, keepConflict: Boolean)
data class AssembledEngine( val engine: DefaultSyncEngine, val scheduler: SyncScheduler, val watcher: VaultWatcher, val remote: EncryptedRemoteStorage, )
class EngineAssembler( private val httpClient: HttpClient, private val cloudFactory: CloudStorageFactory, private val watcherFactory: VaultWatcherFactory, private val ioDispatcher: CoroutineDispatcher, private val scope: CoroutineScope, private val config: () -> RuntimeConfig, )
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
