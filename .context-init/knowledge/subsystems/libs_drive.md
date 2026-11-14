---
directory: libs/drive
source:
  kind: git
  blob_sha: 3f0b215ffc844b59921c8a288d215063cfe583d2
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
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
override suspend fun getLatestChangeToken(): String
override suspend fun getChanges(sinceToken: String): RemoteChangesResult
override suspend fun list(dir: RemotePath): List<RemoteEntry>
override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry>
override suspend fun stat(path: RemotePath): RemoteEntry? = pathResolver.resolvePath(path)?.toRemoteEntry(path)
override suspend fun read(path: RemotePath): Source = downloader.read(path)
override suspend fun write(path: RemotePath, data: Source): RemoteEntry = uploader.upload(path, data)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
