---
directory: packaging/arch
source:
  kind: git
  blob_sha: cc4508e2308d216ba29e24e0c0b19b74ee6dd4dd
  commit_sha: f221fe644add8159ac86eacc977028d32eedc5d8
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

# Подсистема: Packaging (L1)

Модуль и компоненты директории `packaging` (2 файлов)

> **Идентификатор:** `packaging`
> **Теги:** Kotlin (Gradle), packaging

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `packaging/arch/build-arch.sh` | Исходный код |
| `packaging/arch/mimir.sh` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
export _JAVA_AWT_WM_NONREPARENTING=1 exec java -jar /usr/share/java/mimir/mimir.jar "$@"
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
