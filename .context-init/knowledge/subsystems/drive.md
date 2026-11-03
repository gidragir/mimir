---
directory: drive
source:
  kind: git
  blob_sha: f69fac1ee4cb3b71ffdca350555725dd8e74be71
  commit_sha: 0ca8c0bfc25f095f72fe8f8aff5beb38597b19dd
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
