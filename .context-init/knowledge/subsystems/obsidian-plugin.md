---
directory: obsidian-plugin
source:
  kind: git
  blob_sha: 23b67461f0b81bc175e4729241735176d4c25bb9
  commit_sha: 0015ea5942cd9570dd8e5f4a879cead20db73253
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 10
  sampled_entries: 10
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
| `obsidian-plugin/CHANGELOG.md` | Исходный код |
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
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
