---
directory: daemon
source:
  kind: git
  blob_sha: 8c208027580a178e885bec672060186a19cbe568
  commit_sha: e87f1148fd8185cdd0ff7269ac85792979a95a32
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
