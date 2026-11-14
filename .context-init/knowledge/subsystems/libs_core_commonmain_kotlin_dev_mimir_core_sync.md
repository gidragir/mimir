---
directory: libs/core/src/commonMain/kotlin/dev/mimir/core/sync
source:
  kind: git
  blob_sha: f105777c02e8a45783997f3faf6d873e59456aa9
  commit_sha: c830eb78221e86b5127ccb11c7990d275563caa9
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 14
  sampled_entries: 14
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Core CommonMain Kotlin Dev Mimir Core Sync (L1)

Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/sync` (14 файлов)

> **Идентификатор:** `libs_core_commonmain_kotlin_dev_mimir_core_sync`
> **Теги:** Kotlin (Gradle), libs_core_commonmain_kotlin_dev_mimir_core_sync

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/ActionExecutor.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/ConflictFileEntry.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/ConflictResolver.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/DefaultSyncEngine.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/IndexUpdater.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/RemoteExceptions.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/StubSyncEngine.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncEngine.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncIndex.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncIndexJson.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlan.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlanBuilder.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncProgress.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncStateStore.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal class ActionResult( val completedAs: SyncItemAction?, val put: List<FileSyncRecord> = emptyList(), val remove: String? = null, )
fun applyTo(index: SyncIndex): SyncIndex = put.fold(remove?.let(index::withoutRecord) ?: index)
internal class ActionExecutor( private val localVault: LocalVault, private val remoteStorage: RemoteStorage, private val conflictResolver: ConflictResolver, private val dispatcher: CoroutineDispatcher, )
suspend fun localHash(entry: LocalEntry): String = withContext(dispatcher)
suspend fun execute(action: SyncAction, index: SyncIndex): ActionResult = withContext(dispatcher)
internal class LocalIoFailure(val kind: SyncProblemKind, override val cause: Exception) : Exception(cause.message, cause)
internal class DeleteVerificationFailure(override val cause: Exception) : Exception("Не удалось проверить удалённый файл перед удалением: $
override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = try
override fun close() = upstream.close()
internal fun problemOf(path: String, action: SyncItemAction, error: Throwable): SyncProblem
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
