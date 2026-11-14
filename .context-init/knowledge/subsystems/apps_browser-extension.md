---
directory: apps/browser-extension
source:
  kind: git
  blob_sha: 39e3c69039682a9d0869aed956d2e6c4ef8efcab
  commit_sha: 94f3e89990c2a15554a9aeedde2c911da5828074
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

# Подсистема: Browser Extension (L1)

Подсистема `apps/browser-extension` с ключевыми точками входа: apps/browser-extension/build.gradle.kts

> **Идентификатор:** `apps_browser-extension`
> **Теги:** Kotlin (Gradle), apps_browser-extension

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/browser-extension/build.gradle.kts` | ⭐ Точка входа |
| `apps/browser-extension/manifest.template.json` | Исходный код |
| `apps/browser-extension/scripts/capture-screenshots.mjs` | Исходный код |
| `apps/browser-extension/scripts/check-extension.mjs` | Исходный код |
| `apps/browser-extension/webpack.config.d/performance.js` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
