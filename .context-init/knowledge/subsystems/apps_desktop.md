---
directory: apps/desktop/build.gradle.kts
source:
  kind: git
  blob_sha: bfabbfaaf7d6faa0f5296b5e5503f825a48c00ef
  commit_sha: 4afcd4b2c289461cc47f4dc71fea776a420f6dfa
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 1
  sampled_entries: 1
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Desktop (L1)

Подсистема `apps/desktop` с ключевыми точками входа: apps/desktop/build.gradle.kts

> **Идентификатор:** `apps_desktop`
> **Теги:** Kotlin (Gradle), apps_desktop

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/desktop/build.gradle.kts` | ⭐ Точка входа |

## 🔑 Ключевой контракт и экспорты
```text
fun macBundleVersion(version: String): String
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
