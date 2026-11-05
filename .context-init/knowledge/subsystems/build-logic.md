---
directory: build-logic
source:
  kind: git
  blob_sha: a4683cac7b0f097d293306f5de6b6d5562b87fc2
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 5
  sampled_entries: 5
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Build Logic (L1)

Подсистема `build-logic` с ключевыми точками входа: build-logic/build.gradle.kts

> **Идентификатор:** `build-logic`
> **Теги:** Kotlin (Gradle), build-logic

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `build-logic/build.gradle.kts` | ⭐ Точка входа |
| `build-logic/settings.gradle.kts` | Исходный код |
| `build-logic/src/main/kotlin/mimir.android-support.gradle.kts` | Исходный код |
| `build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts` | Исходный код |
| `build-logic/src/main/kotlin/mimir.settings.settings.gradle.kts` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
