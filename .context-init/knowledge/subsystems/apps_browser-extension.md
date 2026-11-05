---
directory: apps/browser-extension
source:
  kind: git
  blob_sha: 8a5b79cf9be903f3e34d58314641cabd0b5b26d8
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 6
  sampled_entries: 6
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
| `apps/browser-extension/karma.config.d/headless.js` | Исходный код |
| `apps/browser-extension/karma.config.d/mocha-timeout.js` | Исходный код |
| `apps/browser-extension/manifest.template.json` | Исходный код |
| `apps/browser-extension/scripts/capture-screenshots.mjs` | Исходный код |
| `apps/browser-extension/scripts/check-extension.mjs` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun googleSecret(name: String) = providers.environmentVariable("MIMIR_BUILD_GOOGLE_$name")
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
