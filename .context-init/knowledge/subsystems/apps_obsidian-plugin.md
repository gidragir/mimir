---
directory: apps/obsidian-plugin
source:
  kind: git
  blob_sha: 8c6681bdf4b199fe9a2f139ab94b2a1492796e25
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 8
  sampled_entries: 8
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Obsidian Plugin (L1)

Подсистема `apps/obsidian-plugin` с ключевыми точками входа: apps/obsidian-plugin/build.gradle.kts

> **Идентификатор:** `apps_obsidian-plugin`
> **Теги:** Kotlin (Gradle), apps_obsidian-plugin

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/obsidian-plugin/build.gradle.kts` | ⭐ Точка входа |
| `apps/obsidian-plugin/manifest.template.json` | Исходный код |
| `apps/obsidian-plugin/scripts/check-plugin.mjs` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/MimirPlugin.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ObsidianApi.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/ObsidianInPlaceApplier.kt` | Исходный код |
| `apps/obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/PluginUpdater.kt` | Исходный код |
| `apps/obsidian-plugin/webpack.config.d/obsidian.js` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class Plugin
class Notice
class MimirPlugin : Plugin()
override fun onload()
override fun onunload()
external class DataAdapter
fun exists(path: String): Promise<Boolean>
fun readBinary(path: String): Promise<ArrayBuffer>
fun writeBinary(path: String, data: ArrayBuffer): Promise<Unit>
external class Vault
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
