---
directory: libs/core/src/commonMain/kotlin/dev/mimir/core/sync
source:
  kind: git
  blob_sha: 8853bc2aa59f39e9ec78b2b16d95f44ffd2b8b3a
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 13
  sampled_entries: 13
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Core CommonMain Kotlin Dev Mimir Core Sync (L1)

Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/sync` (13 файлов)

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
