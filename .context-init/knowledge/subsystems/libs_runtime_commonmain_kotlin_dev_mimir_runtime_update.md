---
directory: libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update
source:
  kind: git
  blob_sha: 1abc06cf4fd04c0add9bd1267b8fab1f9de2de02
  commit_sha: bca5a71aa25e84ea197c3bf1e2540a4de155de19
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

# Подсистема: Runtime CommonMain Kotlin Dev Mimir Runtime Update (L1)

Модуль и компоненты директории `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update` (6 файлов)

> **Идентификатор:** `libs_runtime_commonmain_kotlin_dev_mimir_runtime_update`
> **Теги:** Kotlin (Gradle), libs_runtime_commonmain_kotlin_dev_mimir_runtime_update

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/ApkInstallApplier.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/KtorUpdateHttpClient.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/RuntimeUpdates.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateFacade.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/UpdateScheduler.kt` | Исходный код |
| `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update/VerifiedFileDownloader.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
interface ApkInstallLauncher
fun canRequestInstalls(): Boolean
fun openInstallPermissionSettings()
fun launchInstaller(apk: Path)
interface UpdateQuiesce
suspend fun quiesce(): Boolean
suspend fun resume()
class ApkInstallApplier(...) : UpdateApplier
override suspend fun download( asset: UpdateAsset, release: UpdateRelease, onProgress: (Float, Long, Long) -> Unit, ): Result<DownloadedAsset> = downloader.download(asset, onProgress)
override suspend fun apply(downloadedAsset: DownloadedAsset): UpdateApplyResult
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
