---
directory: libs/drive
source:
  kind: git
  blob_sha: aa76c1109056defa4a851ce6be6122bdc1f372f4
  commit_sha: 973bb96afa606802a1a9c3d251d2eddc26878808
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

# Подсистема: Drive (L1)

Подсистема `libs/drive` с ключевыми точками входа: libs/drive/build.gradle.kts

> **Идентификатор:** `libs_drive`
> **Теги:** Kotlin (Gradle), libs_drive

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/drive/build.gradle.kts` | ⭐ Точка входа |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/DriveDownloader.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/api/DriveDto.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/AccessTokenSource.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/BrowserLogin.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/DeviceCodeFlowRunner.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleAuthClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpException.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/RetryPolicy.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathCache.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathResolver.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/upload/DriveUploader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
internal class DriveDownloader( private val client: DriveHttpClient, private val pathResolver: DrivePathResolver, private val downloadChunkBytes: Long?, )
suspend fun read(path: RemotePath): Source
class GoogleDriveStorage(...) : ChangeFeedStorage
override suspend fun drainAnomalies(): List<RemoteAnomaly> = anomaliesMutex.withLock
override suspend fun getLatestChangeToken(): String
override suspend fun peekChanges(sinceToken: String): ChangePeek
override suspend fun getChanges(sinceToken: String): RemoteChangesResult
override suspend fun list(dir: RemotePath): List<RemoteEntry>
override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry>
override suspend fun stat(path: RemotePath): RemoteEntry? = pathResolver.resolvePath(path)?.toRemoteEntry(path)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
