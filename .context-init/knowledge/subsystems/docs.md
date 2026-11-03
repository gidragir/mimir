---
directory: docs/superpowers
source:
  kind: git
  blob_sha: 6db34f41122e28fd20db7c599a4eeab5b992a4a5
  commit_sha: 59b2f01957ad853d3e73656dd4fdd3f849fcb7f6
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

# Подсистема: Docs (L1)

Модуль и компоненты директории `docs` (15 файлов)

> **Идентификатор:** `docs`
> **Теги:** Kotlin (Gradle), docs

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `docs/superpowers/plans/2026-10-23-mimir-drive.md` | Исходный код |
| `docs/superpowers/plans/2026-10-23-mimir-skeleton.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-daemon.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-desktop-polish.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-desktop.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-obsidian-ui-and-onboarding.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-uri-scheme-login.md` | Исходный код |
| `docs/superpowers/plans/2026-10-26-mimir-improvements.md` | Исходный код |
| `docs/superpowers/plans/2026-10-27-mimir-android.md` | Исходный код |
| `docs/superpowers/plans/2026-11-01-mimir-browser-extension.md` | Исходный код |
| `docs/superpowers/plans/2026-11-03-cross-platform-auto-updates.md` | Исходный код |
| `docs/superpowers/specs/2026-10-24-mimir-daemon-design.md` | Исходный код |
| `docs/superpowers/specs/2026-10-26-mimir-improvements-design.md` | Исходный код |
| `docs/superpowers/specs/2026-11-01-mimir-browser-extension-design.md` | Исходный код |
| `docs/superpowers/specs/2026-11-03-cross-platform-auto-updates-design.md` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class ChangeFeedStorageTest
class RemoteStorageRegistry
class EncryptedRemoteStorage(private val inner: RemoteStorage, private val cipher: Cipher) : RemoteStorage object NotImplementedCipher : Cipher // все методы: throw NotImplementedError("rclone crypt is not implemented yet") sealed interface SyncStatus
class StubSyncEngine : SyncEngine // status всегда NotConfigured; start/stop — no-op; syncNow — NotImplementedError data class Conflict(val id: VaultFileId, val local: ByteArray, val remote: ByteArray) sealed interface Resolution
class InMemoryRemoteStorage : RemoteStorage // для тестов и как образец адаптера data class TimeWindow(...) // в core/rules/SyncRules.kt data class SyncRules(val wifiOnly: Boolean = false, val windows: List<TimeWindow> = emptyList(), val excludedGl...
class FileSystemLocalVault(...) : LocalVault ``` - [ ] **Step 1: Write failing tests in `FileSystemLocalVaultTest`** - Проверка `list()`: рекурсивный обход тестового каталога, игнорирование `.git/**`, `.obsidian/workspace*....
class FileTokenStorage(...) : TokenStorage class JsonSyncStateStore(val indexFile: Path) : SyncStateStore ``` - [ ] **Step 1: Write failing tests** - `DaemonConfigTest`: чтение из `mimir.json`, переопределение переменными окружения (`...
class SyncScheduler( val syncEngine: SyncEngine,
class GoogleAuthClients(val desktop: GoogleAuthClient?, val device: GoogleAuthClient?)
class CanaryCheckTest
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
