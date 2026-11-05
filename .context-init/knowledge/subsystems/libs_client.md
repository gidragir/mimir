---
directory: libs/client
source:
  kind: git
  blob_sha: a9a06e12ef36f685e890981f0c0f41a66f76a8fb
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
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

Модуль и компоненты директории `libs/client` (5 файлов)

> **Идентификатор:** `libs_client`
> **Теги:** Kotlin (Gradle), libs_client

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/client/build.gradle.kts` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt` | Исходный код |
| `libs/client/src/commonMain/kotlin/dev/mimir/client/UpdateDto.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class HttpDaemonClient( private val baseUrl: String, private val token: String? = null, scope: CoroutineScope, private val httpClient: HttpClient = defaultDaemonHttpClient(), pollInterval: Duration = 1.seconds, ) : DaemonService
class UpdateCommandException(...) : IllegalStateException(message) /** * Единственное место, где по тексту ошибки менеджера определяется причина (менеджер отдаёт только строку). * [retryAft...
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
