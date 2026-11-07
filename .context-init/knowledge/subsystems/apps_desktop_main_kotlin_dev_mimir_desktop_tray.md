---
directory: apps/desktop/src/main/kotlin/dev/mimir/desktop/tray
source:
  kind: git
  blob_sha: 5d5bbad223c7c7d3fc70d1d07cce8e1702eed5fc
  commit_sha: 570bdf8b131c69da889f78491ee6adaf106b3095
generated_by:
  component: context-init
  role: update
  model: none
freshness:
  total_entries: 12
  sampled_entries: 12
  unsampled_entries: 0
  pending_child_changes: 0
---

# Подсистема: Desktop Main Kotlin Dev Mimir Desktop Tray (L1)

Модуль и компоненты директории `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray` (12 файлов)

> **Идентификатор:** `apps_desktop_main_kotlin_dev_mimir_desktop_tray`
> **Теги:** Kotlin (Gradle), apps_desktop_main_kotlin_dev_mimir_desktop_tray

## 📁 Точки входа и структура файлов

| Файл | Статус |
| :--- | :--- |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusInterfaces.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/DbusStatusNotifierTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/NoTray.kt` | Исходный код |
| `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/PopoverBehavior.kt` | Исходный код |
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
