---
directory: daemon
source:
  kind: git
  blob_sha: 414e424651056277301d0b75ece408e885a282e0
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 11
  sampled_entries: 11
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
| `daemon/CHANGELOG.md` | Исходный код |
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
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
