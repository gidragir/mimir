---
directory: packaging/arch/mimir.sh
source:
  kind: git
  blob_sha: 640a87765dec5b1536ab895cb4e4f5003996a582
  commit_sha: c4d15d5133f882d1b744714b839e702178c89e3c
generated_by:
  component: context-init
  role: init
  model: none
freshness:
  total_entries: 1
  sampled_entries: 1
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Packaging (L1)

Модуль и компоненты директории `packaging` (1 файлов)

> **Идентификатор:** `packaging`
> **Теги:** Kotlin (Gradle), packaging

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `packaging/arch/mimir.sh` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
export _JAVA_AWT_WM_NONREPARENTING=1 exec java -jar /usr/share/java/mimir/mimir.jar "$@"
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
