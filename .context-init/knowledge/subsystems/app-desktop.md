---
directory: app-desktop
source:
  kind: git
  blob_sha: 49be5606dabd3e7b27a069110ac0e05dc517af96
  commit_sha: 16e76cfbc4c783e4e90380d131b7b1bd19b1ae68
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

# Подсистема: App Desktop (L1)

Подсистема `app-desktop` с ключевыми точками входа: app-desktop/package.json

> **Идентификатор:** `app-desktop`
> **Теги:** Kotlin (Gradle), app-desktop

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `app-desktop/build.gradle.kts` | Исходный код |
| `app-desktop/package.json` | ⭐ Точка входа |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/DaemonServiceFactory.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/PlatformFocus.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/RememberedPassword.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/SystemBrowserOpener.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/ipc/SingleInstanceManager.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/picker/PortalFolderPicker.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusInterfaces.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/Tray.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/update/DesktopUpdateSupport.kt` | Исходный код |
| `app-desktop/src/main/kotlin/dev/mimir/desktop/update/WindowsMsiApplier.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class DesktopBackend(...) : AutoCloseable
```

## 🧪 Запуск и тестирование
```bash
rtk ./gradlew test
```
