---
directory: apps/desktop
source:
  kind: git
  blob_sha: a18b0e5049670c3cf0148fe8163e5894af5469a7
  commit_sha: 77be09c21d6849c3aa20683f5a464c68907f0746
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

# Подсистема: Desktop (L1)

Модуль и компоненты директории `apps/desktop` (15 файлов)

> **Идентификатор:** `apps_desktop`
> **Теги:** Kotlin (Gradle), apps_desktop

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/desktop/build.gradle.kts` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceFactory.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/Main.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/PlatformFocus.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/RememberedPassword.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/SystemBrowserOpener.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/ipc/SingleInstanceManager.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/picker/PortalFolderPicker.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/platform/WindowsProtocolRegistrar.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusInterfaces.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/Tray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/update/DesktopUpdateSupport.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/update/WindowsMsiApplier.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class DesktopBackend(...) : AutoCloseable
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
