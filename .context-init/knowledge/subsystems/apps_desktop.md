---
directory: apps/desktop/build.gradle.kts
source:
  kind: git
  blob_sha: 0d5d4b4bb17fe92fc290cc6540186a5367cf0b2e
  commit_sha: cac1108a6fe7423a5254dd5c6ec22b7131ca5a22
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
