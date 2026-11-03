---
directory: drive
source:
  kind: git
  blob_sha: 2163c553c3f5302e82b7259f34d96af8b000d545
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
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

Подсистема `drive` с ключевыми точками входа: drive/package.json

> **Идентификатор:** `drive`
> **Теги:** Kotlin (Gradle), drive

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `drive/build.gradle.kts` | Исходный код |
| `drive/package.json` | ⭐ Точка входа |
| `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorageProvider.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/api/DriveDto.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/auth/AccessTokenSource.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/auth/BrowserLogin.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleAuthClient.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleClient.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/auth/TokenStorage.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpClient.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpException.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/client/RetryPolicy.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathResolver.kt` | Исходный код |
| `drive/src/commonMain/kotlin/dev/mimir/drive/upload/DriveUploader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class GoogleDriveStorage( private val client: DriveHttpClient? = null, private val pathResolver: DrivePathResolver? = null, ) : ChangeFeedStorage
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
