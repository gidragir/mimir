---
directory: scripts
source:
  kind: git
  blob_sha: fb7ae3d1c322d1718f29e5dbef78ecb392810120
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

# Подсистема: Scripts (L1)

Модуль и компоненты директории `scripts` (2 файлов)

> **Идентификатор:** `scripts`
> **Теги:** Kotlin (Gradle), scripts

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `scripts/gen-rclone-vectors.sh` | Исходный код |
| `scripts/generate-tokens.mjs` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
export function generateKotlin(tokens)
export function generateCss(tokens)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
