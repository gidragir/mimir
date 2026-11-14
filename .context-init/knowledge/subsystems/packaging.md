---
directory: packaging/arch
source:
  kind: git
  blob_sha: 799fde34cfa7ce303deb3c046fd8b3cbf523c36e
  commit_sha: 94f3e89990c2a15554a9aeedde2c911da5828074
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
