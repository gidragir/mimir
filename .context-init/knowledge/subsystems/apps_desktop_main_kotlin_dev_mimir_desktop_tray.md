---
directory: apps/desktop/src/main/kotlin/dev/mimir/desktop/tray
source:
  kind: git
  blob_sha: f2bbe7deb543456b410914c0ef3a9eb6ebedf542
  commit_sha: cac1108a6fe7423a5254dd5c6ec22b7131ca5a22
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 11
  sampled_entries: 11
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Desktop Main Kotlin Dev Mimir Desktop Tray (L1)

Модуль и компоненты директории `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray` (11 файлов)

> **Идентификатор:** `apps_desktop_main_kotlin_dev_mimir_desktop_tray`
> **Теги:** Kotlin (Gradle), apps_desktop_main_kotlin_dev_mimir_desktop_tray

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusInterfaces.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/NoTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/PopoverEscapeDispatcher.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/PopoverVisibilityController.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/ScreenPositionCalculator.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/Tray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/TrayLabel.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/TrayPopoverWindow.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/TrayUpdates.kt` | Исходный код |

## 🔑 Ключевой контракт и экспорты
```text
class AwtTray : Tray
override fun show( status: String, onOpen: () -> Unit, onSyncNow: () -> Unit, onQuit: () -> Unit, onActivate: (TrayClickAnchor?) -> Unit, )
override fun mouseReleased(e: MouseEvent)
override fun update(status: String)
override fun close()
interface StatusNotifierWatcher : DBusInterface
fun RegisterStatusNotifierItem(service: String)
interface StatusNotifierItem : DBusInterface
fun Activate(x: Int, y: Int)
fun SecondaryActivate(x: Int, y: Int)
```

## 🧪 Запуск и тестирование
```bash
rtk mise run test
```
