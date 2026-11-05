---
directory: libs/drive
source:
  kind: git
  blob_sha: a9c182c315da6f0ddd1ece2d8d3d677ebd5eda26
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
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
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorageProvider.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/api/DriveDto.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/AccessTokenSource.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/BrowserLogin.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleAuthClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/SecretStoreTokenStorage.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/TokenStorage.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpException.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/RetryPolicy.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathResolver.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/upload/DriveUploader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class GoogleDriveStorage( private val client: DriveHttpClient? = null, private val pathResolver: DrivePathResolver? = null, ) : ChangeFeedStorage
override suspend fun getLatestChangeToken(): String
override suspend fun getChanges(sinceToken: String): RemoteChangesResult
override suspend fun list(dir: RemotePath): List<RemoteEntry>
override suspend fun listAll(root: RemotePath, onProgress: (folders: Int, files: Int) -> Unit): List<RemoteEntry>
suspend fun walk(folderId: String, dir: RemotePath)
override suspend fun stat(path: RemotePath): RemoteEntry?
override suspend fun read(path: RemotePath): Source
override suspend fun write(path: RemotePath, data: Source): RemoteEntry
override suspend fun delete(path: RemotePath)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
