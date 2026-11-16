---
directory: apps/obsidian-plugin
source:
  kind: git
  blob_sha: 04acb1bb02648790c7b03900d9a606b53b0ef8be
  commit_sha: 973bb96afa606802a1a9c3d251d2eddc26878808
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 5
  sampled_entries: 5
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
| `apps/obsidian-plugin/webpack.config.d/obsidian.js` | Исходный код |
| `apps/obsidian-plugin/webpack.config.d/performance.js` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class Plugin
class Notice
class Modal
class Setting
class PluginSettingTab
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
