---
directory: packaging/arch
source:
  kind: git
  blob_sha: d9971f85e52f228b3f9ed40b07d99a7980babefc
  commit_sha: 26d53005b2cd507dcc6d6b2875e8475b7ca8fb6c
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
