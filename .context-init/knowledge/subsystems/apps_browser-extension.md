---
directory: apps/browser-extension
source:
  kind: git
  blob_sha: 6f58ed8664fd5af22dceca3d5e3fd01b061b3429
  commit_sha: f221fe644add8159ac86eacc977028d32eedc5d8
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
