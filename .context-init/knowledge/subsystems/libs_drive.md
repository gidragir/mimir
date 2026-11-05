---
directory: libs/drive
source:
  kind: git
  blob_sha: a9c182c315da6f0ddd1ece2d8d3d677ebd5eda26
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
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

Модуль и компоненты директории `libs/drive` (15 файлов)

> **Идентификатор:** `libs_drive`
> **Теги:** Kotlin (Gradle), libs_drive

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/drive/build.gradle.kts` | Исходный код |
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
class RefreshingTokenSource( private val storage: TokenStorage, private val clients: GoogleAuthClients, private val clock: Clock = Clock.System, ) : AccessTokenSource
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
