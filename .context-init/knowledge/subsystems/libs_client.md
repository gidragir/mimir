---
directory: libs/client
source:
  kind: git
  blob_sha: daed662bb3571f146f6c63ff6fb1481be1f00213
  commit_sha: 973bb96afa606802a1a9c3d251d2eddc26878808
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 6
  sampled_entries: 6
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Client (L1)

Подсистема `libs/client` с ключевыми точками входа: libs/client/build.gradle.kts

> **Идентификатор:** `libs_client`
> **Теги:** Kotlin (Gradle), libs_client

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/client/build.gradle.kts` | ⭐ Точка входа |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonHttpTransport.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/UpdateDto.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun DaemonSyncProgressDto.toModel(...): SyncProgress = SyncProgress( phase = parseSnake<SyncPhase>(phase), startedAt = Instant.parse(startedAt), finishedAt = finishedAt?.let(Instant::parse), applyingStartedAt = applyingStartedAt?.let(Instant::parse), scanned = scanned, folders...
data class DaemonStatusDto(...)
data class DaemonResolveDeletionsRequest( val decision: String, // "delete" | "keep" )
data class DaemonSyncProgressDto( val phase: String, val startedAt: String, val finishedAt: String? = null, val applyingStartedAt: String? = null, val scanned: Int = 0, val foldersScanned: Int = 0, val total: Int = 0, val done: Int = 0, val bytesTotal: Long = 0, val bytesDone:...
data class DaemonSyncItemDto( val path: String, val action: String, )
data class DaemonCompletedItemDto( val path: String, val action: String, val at: String, )
data class DaemonSyncProblemDto( val path: String, val action: String, val kind: String, val detail: String, )
data class DaemonConflictItemDto( val originalPath: String, val conflictPath: String, val createdAt: String, )
data class DaemonConflictsResponse( val conflicts: List<DaemonConflictItemDto>, )
data class DaemonResolveConflictRequest( val originalPath: String, val conflictPath: String, val resolution: String, // "keep_original" | "keep_conflict" )
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
