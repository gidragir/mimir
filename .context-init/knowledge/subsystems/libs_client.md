---
directory: libs/client
source:
  kind: git
  blob_sha: a9a06e12ef36f685e890981f0c0f41a66f76a8fb
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 5
  sampled_entries: 5
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
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/UpdateDto.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun DaemonSyncProgressDto.toModel(...): SyncProgress = SyncProgress( phase = parseSnake<SyncPhase>(phase), startedAt = Instant.parse(startedAt), finishedAt = finishedAt?.let(Instant::parse), applyingStartedAt = applyingStartedAt?.let(Instant::parse), scanned = scanned, folders...
data class DaemonStatusDto( val status: String, val label: String, val vaultPath: String? = null, val lastSyncAt: String? = null, val isLocked: Boolean, val activeConflicts: Int, val accountEmail: String? = null, val progress: DaemonSyncProgressDto? = null, )
data class DaemonSyncProgressDto( val phase: String, val startedAt: String, val finishedAt: String? = null, val applyingStartedAt: String? = null, val scanned: Int = 0, val foldersScanned: Int = 0, val total: Int = 0, val done: Int = 0, val bytesTotal: Long = 0, val bytesDone:...
data class DaemonSyncItemDto( val path: String, val action: String, )
data class DaemonCompletedItemDto( val path: String, val action: String, val at: String, )
data class DaemonSyncProblemDto( val path: String, val action: String, val kind: String, val detail: String, )
data class DaemonConflictItemDto( val originalPath: String, val conflictPath: String, val createdAt: String, )
data class DaemonConflictsResponse( val conflicts: List<DaemonConflictItemDto>, )
data class DaemonResolveConflictRequest( val originalPath: String, val conflictPath: String, val resolution: String, // "keep_original" | "keep_conflict" )
data class DaemonResolveConflictResponse( val status: String, val path: String, )
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
