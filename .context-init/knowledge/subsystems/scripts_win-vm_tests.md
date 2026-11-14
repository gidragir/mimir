---
directory: scripts/win-vm/tests
source:
  kind: git
  blob_sha: 18241648e6702988127073b2ce8ca5b2ce121f77
  commit_sha: 94f3e89990c2a15554a9aeedde2c911da5828074
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 9
  sampled_entries: 9
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Scripts Win Vm Tests (L1)

Тесты для `scripts_win-vm`

> **Идентификатор:** `scripts_win-vm_tests`
> **Теги:** Kotlin (Gradle), scripts_win-vm_tests, tests

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `scripts/win-vm/tests/fakes/guest-agent.sh` | Исходный код |
| `scripts/win-vm/tests/helpers.sh` | Исходный код |
| `scripts/win-vm/tests/run.sh` | Исходный код |
| `scripts/win-vm/tests/test_lib.sh` | Исходный код |
| `scripts/win-vm/tests/test_msi.sh` | Исходный код |
| `scripts/win-vm/tests/test_provision.sh` | Исходный код |
| `scripts/win-vm/tests/test_run.sh` | Исходный код |
| `scripts/win-vm/tests/test_snapshot.sh` | Исходный код |
| `scripts/win-vm/tests/test_sync.sh` | Исходный код |

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
