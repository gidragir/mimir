---
directory: web-ui
source:
  kind: git
  blob_sha: 1ec77e87e9dcade759dcd04703ae15a8a1efc610
  commit_sha: 0015ea5942cd9570dd8e5f4a879cead20db73253
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
