---
directory: web-ui
source:
  kind: git
  blob_sha: 3332400ee1c51d10a82ba6fe1033f9ffa551e93f
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 4
  sampled_entries: 4
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Web Ui (L1)

Подсистема `web-ui` с ключевыми точками входа: web-ui/package.json

> **Идентификатор:** `web-ui`
> **Теги:** Kotlin (Gradle), web-ui

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `web-ui/CHANGELOG.md` | Исходный код |
| `web-ui/build.gradle.kts` | Исходный код |
| `web-ui/package.json` | ⭐ Точка входа |
| `web-ui/src/wasmJsMain/kotlin/dev/mimir/web/Main.kt` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
