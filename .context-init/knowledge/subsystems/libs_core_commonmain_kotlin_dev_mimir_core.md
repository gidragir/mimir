---
directory: libs/core/src/commonMain/kotlin/dev/mimir/core
source:
  kind: git
  blob_sha: a4a3bab4fc1a8bef8570e610ee5a8cb92fe81ef9
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 15
  sampled_entries: 15
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Core CommonMain Kotlin Dev Mimir Core (L1)

Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core` (15 файлов)

> **Идентификатор:** `libs_core_commonmain_kotlin_dev_mimir_core`
> **Теги:** Kotlin (Gradle), libs_core_commonmain_kotlin_dev_mimir_core

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/api/LocalVault.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/api/RemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/fs/FileSystemLocalVault.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/fs/PollingVaultWatcher.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/rules/GlobMatcher.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/CountingRemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryLocalVault.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryRemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/DailyUpdateChecker.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/DefaultUpdateManager.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/GitHubReleasesChannel.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/InPlaceBundleApplier.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/SemVer.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/UpdateApplier.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/update/UpdateModels.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
value class VaultFileId(val value: String)
data class LocalEntry( val id: VaultFileId, val relativePath: String, val size: Long, val modifiedAt: Instant, )
sealed interface VaultChange
data class Created(override val id: VaultFileId, val entry: LocalEntry) : VaultChange
data class Modified(override val id: VaultFileId, val entry: LocalEntry) : VaultChange
data class Deleted(override val id: VaultFileId) : VaultChange
interface LocalVault
suspend fun list(): List<LocalEntry>
suspend fun getEntry(relativePath: String): LocalEntry?
suspend fun read(id: VaultFileId): Source
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
