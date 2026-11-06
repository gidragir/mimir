---
directory: design
source:
  kind: git
  blob_sha: 4cc6a1f188783dadd2636d06bf94269166dd6542
  commit_sha: f221fe644add8159ac86eacc977028d32eedc5d8
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 3
  sampled_entries: 3
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Design (L1)

Модуль и компоненты директории `design` (3 файлов)

> **Идентификатор:** `design`
> **Теги:** Kotlin (Gradle), design

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `design/generate-tokens.mjs` | Исходный код |
| `design/logo/README.md` | Исходный код |
| `design/tokens.json` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
export function generateKotlin(tokens)
export function generateCss(tokens)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
