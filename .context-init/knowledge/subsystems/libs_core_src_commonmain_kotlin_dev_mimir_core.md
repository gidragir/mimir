---
directory: libs/core/src/commonMain/kotlin/dev/mimir/core
source:
  kind: git
  blob_sha: a4a3bab4fc1a8bef8570e610ee5a8cb92fe81ef9
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

# Подсистема: Libs Core Src CommonMain Kotlin Dev Mimir Core (L1)

Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core` (15 файлов)

> **Идентификатор:** `libs_core_src_commonmain_kotlin_dev_mimir_core`
> **Теги:** Kotlin (Gradle), libs_core_src_commonmain_kotlin_dev_mimir_core

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/api/RemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/rules/GlobMatcher.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/ActionExecutor.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/ConflictResolver.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/DefaultSyncEngine.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlan.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/sync/SyncPlanBuilder.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/CountingRemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryLocalVault.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryRemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/DefaultUpdateManager.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/GitHubReleasesChannel.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/InPlaceBundleApplier.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/SemVer.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class FileSystemLocalVault(...) : LocalVault
class DefaultConflictResolver( private val clock: Clock = Clock.System, ) : ConflictResolver
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
