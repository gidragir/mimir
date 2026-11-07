---
directory: scripts/win-vm
source:
  kind: git
  blob_sha: de331bd6efc702822db81c954856c6b205344155
  commit_sha: cac1108a6fe7423a5254dd5c6ec22b7131ca5a22
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 15
  sampled_entries: 15
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Scripts (L1)

Модуль и компоненты директории `scripts` (15 файлов)

> **Идентификатор:** `scripts`
> **Теги:** Kotlin (Gradle), scripts

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `scripts/win-vm/guest/agent.ps1` | Исходный код |
| `scripts/win-vm/guest/provision.ps1` | Исходный код |
| `scripts/win-vm/guest/stop.ps1` | Исходный код |
| `scripts/win-vm/guest/sync.ps1` | Исходный код |
| `scripts/win-vm/lib.sh` | Исходный код |
| `scripts/win-vm/tests/fakes/guest-agent.sh` | Исходный код |
| `scripts/win-vm/tests/helpers.sh` | Исходный код |
| `scripts/win-vm/tests/run.sh` | Исходный код |
| `scripts/win-vm/tests/test_lib.sh` | Исходный код |
| `scripts/win-vm/tests/test_msi.sh` | Исходный код |
| `scripts/win-vm/tests/test_provision.sh` | Исходный код |
| `scripts/win-vm/tests/test_run.sh` | Исходный код |
| `scripts/win-vm/tests/test_snapshot.sh` | Исходный код |
| `scripts/win-vm/tests/test_sync.sh` | Исходный код |
| `scripts/win-vm/winvm.sh` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
export WINVM_NAME WINVM_URI WINVM_SHARE WINVM_SHARE_DRIVE WINVM_AGENT_TIMEOUT \ WINVM_POLL WINVM_EXEC_POLL WINVM_EXEC_TIMEOUT WINVM_MISE_VERSION WINVM_STATUS_TIMEOUT WINVM_STATUS_POLL WINVM_HOST_DIR WINVM_GUEST_DIR } _virsh()
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
