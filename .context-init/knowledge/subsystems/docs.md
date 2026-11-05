---
directory: docs/superpowers
source:
  kind: git
  blob_sha: 997acf8eaa1597a2ca90cb6eae4cd0b3ceb065d3
  commit_sha: 647d02d99ae62690f5111cb5653e49f6651aafb5
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
| `docs/superpowers/plans/2026-10-24-mimir-desktop-polish.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-desktop.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-obsidian-ui-and-onboarding.md` | Исходный код |
| `docs/superpowers/plans/2026-10-24-mimir-uri-scheme-login.md` | Исходный код |
| `docs/superpowers/plans/2026-10-26-mimir-improvements.md` | Исходный код |
| `docs/superpowers/plans/2026-10-27-mimir-android.md` | Исходный код |
| `docs/superpowers/plans/2026-11-01-mimir-browser-extension.md` | Исходный код |
| `docs/superpowers/plans/2026-11-03-cross-platform-auto-updates.md` | Исходный код |
| `docs/superpowers/plans/2026-11-03-web-ui-docker.md` | Исходный код |
| `docs/superpowers/plans/2026-11-05-monorepo-structure-and-ci-pipeline.md` | Исходный код |
| `docs/superpowers/specs/2026-10-24-mimir-daemon-design.md` | Исходный код |
| `docs/superpowers/specs/2026-11-01-mimir-browser-extension-design.md` | Исходный код |
| `docs/superpowers/specs/2026-11-03-cross-platform-auto-updates-design.md` | Исходный код |
| `docs/superpowers/specs/2026-11-04-monorepo-structure-and-ci-pipeline-design.md` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class ChangeFeedStorageTest
class GoogleAuthClients(val desktop: GoogleAuthClient?, val device: GoogleAuthClient?)
class CanaryCheckTest
class ConfigureVaultTest
class MimirThemeTest
class AwtFolderPickerTest
class SingleInstanceManager(socketPath: Path? = null) : AutoCloseable
class SingleInstanceManagerTest
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
