---
directory: apps/web-ui
source:
  kind: git
  blob_sha: 40f7a5730a6cab18721ef2078cc0e07d7139b638
  commit_sha: d76124709e74a48c435fa5562976a03d7ddce70f
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 3
  sampled_entries: 3
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Web Ui (L1)

Подсистема `apps/web-ui` с ключевыми точками входа: apps/web-ui/build.gradle.kts

> **Идентификатор:** `apps_web-ui`
> **Теги:** Kotlin (Gradle), apps_web-ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/web-ui/build.gradle.kts` | ⭐ Точка входа |
| `apps/web-ui/src/wasmJsMain/kotlin/dev/mimir/web/Main.kt` | Исходный код |
| `apps/web-ui/webpack.config.d/performance.js` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
fun main()
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
