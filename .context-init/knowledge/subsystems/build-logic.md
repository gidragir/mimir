---
directory: build-logic
source:
  kind: git
  blob_sha: 03b98b12e0b4ee1fbd4c3c1bf5ee2038ce8ac9af
  commit_sha: 799cec16d9d3f57d366553b65e11bd0d24956012
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 6
  sampled_entries: 6
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
| `build-logic/src/main/kotlin/mimir.jvm.gradle.kts` | Исходный код |
| `build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts` | Исходный код |
| `build-logic/src/main/kotlin/mimir.settings.settings.gradle.kts` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
