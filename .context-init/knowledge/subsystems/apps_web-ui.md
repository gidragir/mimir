---
directory: apps/web-ui
source:
  kind: git
  blob_sha: c1d25b54fa0cabceff408854f9a1b2d316540431
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 2
  sampled_entries: 2
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

## 🔑 Ключевой контракт и экспорты
```text
fun main()
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
