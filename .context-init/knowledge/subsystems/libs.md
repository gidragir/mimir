---
directory: libs
source:
  kind: git
  blob_sha: 1b87c69ac0e4604dab362e57d6971a9d69535dab
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

# Подсистема: Libs (L1)

Модуль и компоненты директории `libs` (15 файлов)

> **Идентификатор:** `libs`
> **Теги:** Kotlin (Gradle), libs

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/UpdateDto.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorageProvider.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/api/DriveDto.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/AccessTokenSource.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/BrowserLogin.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/auth/GoogleAuthClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpClient.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpException.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/client/RetryPolicy.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/path/DrivePathResolver.kt` | Исходный код |
| `libs/drive/src/commonMain/kotlin/dev/mimir/drive/upload/DriveUploader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class HttpDaemonClient( private val baseUrl: String, private val token: String? = null, scope: CoroutineScope, private val httpClient: HttpClient = defaultDaemonHttpClient(), pollInterval: Duration = 1.seconds, ) : DaemonService
class UpdateCommandException(...) : IllegalStateException(message) /** * Единственное место, где по тексту ошибки менеджера определяется причина (менеджер отдаёт только строку). * [retryAft...
class GoogleDriveStorage( private val client: DriveHttpClient? = null, private val pathResolver: DrivePathResolver? = null, ) : ChangeFeedStorage
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
