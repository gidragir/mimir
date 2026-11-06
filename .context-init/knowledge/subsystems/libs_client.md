---
directory: libs/client
source:
  kind: git
  blob_sha: 4b73e85fb7e15610db2dc329a1bea2a9c66636ff
  commit_sha: bca5a71aa25e84ea197c3bf1e2540a4de155de19
generated_by:
  component: context-init
  role: update
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
