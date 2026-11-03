---
directory: daemon
source:
  kind: git
  blob_sha: 6958fa16ac5e95505ff09964be7bb5563e9673f1
  commit_sha: 59b2f01957ad853d3e73656dd4fdd3f849fcb7f6
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 10
  sampled_entries: 10
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Daemon (L1)

Подсистема `daemon` с ключевыми точками входа: daemon/package.json

> **Идентификатор:** `daemon`
> **Теги:** Kotlin (Gradle), daemon

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `daemon/build.gradle.kts` | Исходный код |
| `daemon/package.json` | ⭐ Точка входа |
| `daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt` | Исходный код |
| `daemon/src/main/kotlin/dev/mimir/daemon/DaemonService.kt` | Исходный код |
| `daemon/src/main/kotlin/dev/mimir/daemon/DockerLogNotifyApplier.kt` | Исходный код |
| `daemon/src/main/kotlin/dev/mimir/daemon/GoogleClients.kt` | Исходный код |
| `daemon/src/main/kotlin/dev/mimir/daemon/Main.kt` | Исходный код |
| `daemon/src/main/kotlin/dev/mimir/daemon/ProgressDto.kt` | Исходный код |
| `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt` | Исходный код |
| `daemon/src/main/kotlin/dev/mimir/daemon/UpdateRoutes.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class DaemonSettingsStore(initial: DaemonConfig) : RuntimeSettingsStore
class DockerLogNotifyApplier( private val log: (String) -> Unit = applierLogger::info, ) : UpdateApplier
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
