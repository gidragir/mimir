---
directory: apps/desktop/build.gradle.kts
source:
  kind: git
  blob_sha: fbb6378b3b364e21bfc8c0fcf63dc6354848812e
  commit_sha: 570bdf8b131c69da889f78491ee6adaf106b3095
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
abstract class DevAppLogService : BuildService<BuildServiceParameters.None>, AutoCloseable
fun open(path: String): OutputStream = FileOutputStream(path, true).also
override fun close()
fun macBundleVersion(version: String): String
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
