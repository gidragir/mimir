---
directory: scripts
source:
  kind: git
  blob_sha: fb7ae3d1c322d1718f29e5dbef78ecb392810120
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
generated_by:
  component: context-init
  role: update
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
rtk ./gradlew test
```
