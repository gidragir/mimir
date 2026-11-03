---
directory: client
source:
  kind: git
  blob_sha: 5e6fc1cd8f48d61f9965d2b876f120883feb0ecb
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 7
  sampled_entries: 7
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
| `client/CHANGELOG.md` | Исходный код |
| `client/build.gradle.kts` | Исходный код |
| `client/package.json` | ⭐ Точка входа |
| `client/src/commonMain/kotlin/dev/mimir/client/DaemonDto.kt` | Исходный код |
| `client/src/commonMain/kotlin/dev/mimir/client/DaemonService.kt` | Исходный код |
| `client/src/commonMain/kotlin/dev/mimir/client/HttpDaemonClient.kt` | Исходный код |
| `client/src/commonMain/kotlin/dev/mimir/client/UpdateDto.kt` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
