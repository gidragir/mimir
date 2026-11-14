---
directory: apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian
source:
  kind: git
  blob_sha: 6e851be12e276529ed2ab17aa3800e8dd6184b67
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
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

# Подсистема: Obsidian Plugin JsMain Kotlin Dev Mimir Obsidian (L1)

Модуль и компоненты директории `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian` (15 файлов)

> **Идентификатор:** `apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian`
> **Теги:** Kotlin (Gradle), apps_obsidian-plugin_jsmain_kotlin_dev_mimir_obsidian

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/MimirPlugin.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ObsidianApi.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/daemon/DaemonProbe.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/diag/DiagnosticsCommand.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/net/ObsidianHttpEngine.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/platform/Bridges.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/state/DeviceSecretStore.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/state/ObsidianSyncStateStore.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/state/PluginSettings.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/sync/PluginSyncController.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/sync/SignIn.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/sync/SyncAssembler.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/ObsidianInPlaceApplier.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/PluginUpdater.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/vault/ObsidianLocalVault.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class MimirPlugin(app: App, manifest: PluginManifest) : Plugin(app, manifest)
override fun onload()
override fun onunload()
external class DataAdapter
fun exists(path: String): Promise<Boolean>
fun readBinary(path: String): Promise<ArrayBuffer>
fun writeBinary(path: String, data: ArrayBuffer): Promise<Unit>
fun appendBinary(path: String, data: ArrayBuffer): Promise<Unit>
fun getResourcePath(path: String): String
fun rename(from: String, to: String): Promise<Unit>
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
