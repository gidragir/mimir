---
directory: build-logic
source:
  kind: git
  blob_sha: 8081b260aacd7ace57f7f7c3c5e9c5f5be0ce3eb
  commit_sha: 94f3e89990c2a15554a9aeedde2c911da5828074
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

## 🔑 Ключевой контракт и экспорты
```text
fun sdk(alias: String) = catalog.findVersion(alias).get().requiredVersion.toInt()
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
