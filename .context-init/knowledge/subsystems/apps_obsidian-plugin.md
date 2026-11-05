---
directory: apps/obsidian-plugin
source:
  kind: git
  blob_sha: 8c6681bdf4b199fe9a2f139ab94b2a1492796e25
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 8
  sampled_entries: 8
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Obsidian Plugin (L1)

Модуль и компоненты директории `apps/obsidian-plugin` (8 файлов)

> **Идентификатор:** `apps_obsidian-plugin`
> **Теги:** Kotlin (Gradle), apps_obsidian-plugin

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/obsidian-plugin/build.gradle.kts` | Исходный код |
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
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
