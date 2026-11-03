---
directory: client
source:
  kind: git
  blob_sha: 7215054c0fc023cb5a2d99c4db46739f96b876d0
  commit_sha: 59b2f01957ad853d3e73656dd4fdd3f849fcb7f6
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 6
  sampled_entries: 6
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Client (L1)

Подсистема `client` с ключевыми точками входа: client/package.json

> **Идентификатор:** `client`
> **Теги:** Kotlin (Gradle), client

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `client/build.gradle.kts` | Исходный код |
| `client/package.json` | ⭐ Точка входа |
| `client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt` | Исходный код |
| `client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt` | Исходный код |
| `client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt` | Исходный код |
| `client/src/commonMain/kotlin/dev/mimir/client/UpdateDto.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class HttpDaemonClient( private val baseUrl: String, private val token: String? = null, scope: CoroutineScope, private val httpClient: HttpClient = defaultDaemonHttpClient(), pollInterval: Duration = 1.seconds, ) : DaemonService
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
