---
directory: obsidian-plugin
source:
  kind: git
  blob_sha: d0187adaed1346cfda9a784f97a8eaadbfef29fa
  commit_sha: 59b2f01957ad853d3e73656dd4fdd3f849fcb7f6
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

# Подсистема: Obsidian Plugin (L1)

Подсистема `obsidian-plugin` с ключевыми точками входа: obsidian-plugin/package.json

> **Идентификатор:** `obsidian-plugin`
> **Теги:** Kotlin (Gradle), obsidian-plugin

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `obsidian-plugin/build.gradle.kts` | Исходный код |
| `obsidian-plugin/manifest.template.json` | Исходный код |
| `obsidian-plugin/package.json` | ⭐ Точка входа |
| `obsidian-plugin/scripts/check-plugin.mjs` | Исходный код |
| `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/MimirPlugin.kt` | Исходный код |
| `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ObsidianApi.kt` | Исходный код |
| `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/ObsidianInPlaceApplier.kt` | Исходный код |
| `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/update/PluginUpdater.kt` | Исходный код |
| `obsidian-plugin/webpack.config.d/obsidian.js` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class Plugin
class Notice
class MimirPlugin : Plugin()
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
