---
directory: apps/browser-extension
source:
  kind: git
  blob_sha: 2c4a4367bf1a02b6d08d4e456a0d62f5ae69e8d4
  commit_sha: 26d53005b2cd507dcc6d6b2875e8475b7ca8fb6c
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

## 🔑 Ключевой контракт и экспорты
```text
fun googleSecret(name: String) = providers.environmentVariable("MIMIR_BUILD_GOOGLE_$name")
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
