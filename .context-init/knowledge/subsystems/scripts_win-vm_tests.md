---
directory: scripts/win-vm/tests
source:
  kind: git
  blob_sha: b2c3d9b13b7d1e89cae9140c757b4cfec67d4a6b
  commit_sha: c830eb78221e86b5127ccb11c7990d275563caa9
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
