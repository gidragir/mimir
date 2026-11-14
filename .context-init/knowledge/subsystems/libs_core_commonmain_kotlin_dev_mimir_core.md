---
directory: libs/core/src/commonMain/kotlin/dev/mimir/core
source:
  kind: git
  blob_sha: cbab0bfc1f0bba14cb5b9cf277b0f978ed77cb8d
  commit_sha: 98076efaaebe3ac2f8c73f43d4ed088e48d2006a
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
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/CountingLocalVault.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/CountingRemoteStorage.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryLocalVault.kt` | Исходный код |
| `libs/core/src/commonMain/kotlin/dev/mimir/core/testing/InMemoryRemoteStorage.kt` | Исходный код |
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
