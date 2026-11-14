---
directory: scripts/win-vm
source:
  kind: git
  blob_sha: 99dd2b309eeecc8727522dc9475993b74f79fe54
  commit_sha: e735cb52ed681f464f3b8e62310c00a288fd4504
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 6
  sampled_entries: 6
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Scripts Win Vm (L1)

Модуль и компоненты директории `scripts/win-vm` (6 файлов)

> **Идентификатор:** `scripts_win-vm`
> **Теги:** Kotlin (Gradle), scripts_win-vm

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `scripts/win-vm/guest/agent.ps1` | Исходный код |
| `scripts/win-vm/guest/provision.ps1` | Исходный код |
| `scripts/win-vm/guest/stop.ps1` | Исходный код |
| `scripts/win-vm/guest/sync.ps1` | Исходный код |
| `scripts/win-vm/lib.sh` | Исходный код |
| `scripts/win-vm/winvm.sh` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
export WINVM_NAME WINVM_URI WINVM_SHARE WINVM_SHARE_DRIVE WINVM_AGENT_TIMEOUT \ WINVM_POLL WINVM_EXEC_POLL WINVM_EXEC_TIMEOUT WINVM_MISE_VERSION WINVM_STATUS_TIMEOUT WINVM_STATUS_POLL WINVM_HOST_DIR WINVM_GUEST_DIR } _virsh()
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
