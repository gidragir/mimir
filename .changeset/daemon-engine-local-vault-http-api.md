---
"@mimir/core": minor
"@mimir/daemon": minor
---

Демон: `FileSystemLocalVault` с атомарной записью и `VaultWatcher` (WatchService/polling, debounce, подавление эхо), `SyncScheduler` и `DaemonRuntime`, конфигурация/токены (0600)/индекс в `~/.config/mimir`, локальный HTTP API v1 (status, sync, unlock, conflicts, Device Code Grant)
