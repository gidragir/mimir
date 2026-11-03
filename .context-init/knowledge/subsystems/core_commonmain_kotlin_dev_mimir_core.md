---
directory: core/src/commonMain/kotlin/dev/mimir/core
source:
  kind: git
  blob_sha: 130bda596a159658be9e3faa046179c6d40330d8
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
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

# Подсистема: Core CommonMain Kotlin Dev Mimir Core (L1)

Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core` (15 файлов)

> **Идентификатор:** `core_commonmain_kotlin_dev_mimir_core`
> **Теги:** Kotlin (Gradle), core_commonmain_kotlin_dev_mimir_core

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `core/src/commonMain/kotlin/dev/mimir/core/api/RemoteStorage.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/rules/GlobMatcher.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/sync/ActionExecutor.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/sync/ConflictResolver.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/sync/DefaultSyncEngine.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlan.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlanBuilder.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/testing/CountingRemoteStorage.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryLocalVault.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryRemoteStorage.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/update/DefaultUpdateManager.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/update/GitHubReleasesChannel.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/update/InPlaceBundleApplier.kt` | Исходный код |
| `core/src/commonMain/kotlin/dev/mimir/core/update/SemVer.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class FileSystemLocalVault(...) : LocalVault
class DefaultConflictResolver( private val clock: Clock = Clock.System, ) : ConflictResolver
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
