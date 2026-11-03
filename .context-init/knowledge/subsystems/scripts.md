---
directory: scripts
source:
  kind: git
  blob_sha: d5592203e182cc7aa3b919b7a36d54457ddbe7b1
  commit_sha: 0ca8c0bfc25f095f72fe8f8aff5beb38597b19dd
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

# Подсистема: Scripts (L1)

Модуль и компоненты директории `scripts` (4 файлов)

> **Идентификатор:** `scripts`
> **Теги:** Kotlin (Gradle), scripts

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `scripts/gen-rclone-vectors.sh` | Исходный код |
| `scripts/generate-tokens.mjs` | Исходный код |
| `scripts/sync-version.mjs` | Исходный код |
| `scripts/tag-release.mjs` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
export function generateKotlin(tokens)
export function generateCss(tokens)
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
