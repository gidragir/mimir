---
directory: apps/daemon
source:
  kind: git
  blob_sha: a1682b793767034db55ba014ff45b18ab6afeab8
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 9
  sampled_entries: 9
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Daemon (L1)

Модуль и компоненты директории `apps/daemon` (9 файлов)

> **Идентификатор:** `apps_daemon`
> **Теги:** Kotlin (Gradle), apps_daemon

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/daemon/build.gradle.kts` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/DaemonService.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/DockerLogNotifyApplier.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/GoogleClients.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/Main.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/ProgressDto.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt` | Исходный код |
| `apps/daemon/src/main/kotlin/dev/mimir/daemon/UpdateRoutes.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class DaemonSettingsStore(initial: DaemonConfig) : RuntimeSettingsStore
class DockerLogNotifyApplier( private val log: (String) -> Unit = applierLogger::info, ) : UpdateApplier
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
