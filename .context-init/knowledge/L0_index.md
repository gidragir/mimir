# 🗺 Карта архитектуры: mimir (L0)

> **Стек:** Kotlin (Gradle) | **Обновлено:** 2026-11-08
> **Описание:** Один открытый инструмент синхронизации Obsidian-хранилища на всех платформах (Linux, Windows, Android). Он ощущается как Google Drive Desktop: один раз указал папку vault и пароль, дальше всё синхронизируется само.

## 🚪 Точки входа (Root Entrypoints)
- `mise.toml`
- `build.gradle.kts`
- `settings.gradle.kts`

## ⚡️ Быстрые команды
```bash
# Сборка
rtk mise run build

# Тесты
rtk mise run test

# Запуск
rtk mise run desktop

```

## 🧩 Каталог подсистем (L1)

| Подсистема | Роль / Назначение | Точки входа | L1 Карточка |
| :--- | :--- | :--- | :--- |
| **Android** | Подсистема `apps/android` с ключевыми точками входа: apps/android/build.gradle.kts | `apps/android/build.gradle.kts` | [subsystems/apps_android.md](subsystems/apps_android.md) |
| **Browser Extension** | Подсистема `apps/browser-extension` с ключевыми точками входа: apps/browser-extension/build.gradle.kts | `apps/browser-extension/build.gradle.kts` | [subsystems/apps_browser-extension.md](subsystems/apps_browser-extension.md) |
| **Browser Extension JsMain** | Модуль и компоненты директории `apps/browser-extension/src/jsMain` (3 файлов) | `-` | [subsystems/apps_browser-extension_jsmain.md](subsystems/apps_browser-extension_jsmain.md) |
| **Browser Extension JsMain Kotlin Dev Mimir Extension** | Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension` (8 файлов) | `-` | [subsystems/apps_browser-extension_jsmain_kotlin_dev_mimir_extension.md](subsystems/apps_browser-extension_jsmain_kotlin_dev_mimir_extension.md) |
| **Browser Extension JsMain Kotlin Dev Mimir Extension Ui** | Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui` (8 файлов) | `-` | [subsystems/apps_browser-extension_jsmain_kotlin_dev_mimir_extension_ui.md](subsystems/apps_browser-extension_jsmain_kotlin_dev_mimir_extension_ui.md) |
| **Daemon** | Подсистема `apps/daemon` с ключевыми точками входа: apps/daemon/build.gradle.kts | `apps/daemon/build.gradle.kts` | [subsystems/apps_daemon.md](subsystems/apps_daemon.md) |
| **Desktop** | Подсистема `apps/desktop` с ключевыми точками входа: apps/desktop/build.gradle.kts | `apps/desktop/build.gradle.kts` | [subsystems/apps_desktop.md](subsystems/apps_desktop.md) |
| **Desktop Main Kotlin Dev Mimir Desktop** | Модуль и компоненты директории `apps/desktop/src/main/kotlin/dev/mimir/desktop` (15 файлов) | `-` | [subsystems/apps_desktop_main_kotlin_dev_mimir_desktop.md](subsystems/apps_desktop_main_kotlin_dev_mimir_desktop.md) |
| **Desktop Main Kotlin Dev Mimir Desktop Tray** | Модуль и компоненты директории `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray` (12 файлов) | `-` | [subsystems/apps_desktop_main_kotlin_dev_mimir_desktop_tray.md](subsystems/apps_desktop_main_kotlin_dev_mimir_desktop_tray.md) |
| **Obsidian Plugin** | Подсистема `apps/obsidian-plugin` с ключевыми точками входа: apps/obsidian-plugin/build.gradle.kts | `apps/obsidian-plugin/build.gradle.kts` | [subsystems/apps_obsidian-plugin.md](subsystems/apps_obsidian-plugin.md) |
| **Web Ui** | Подсистема `apps/web-ui` с ключевыми точками входа: apps/web-ui/build.gradle.kts | `apps/web-ui/build.gradle.kts` | [subsystems/apps_web-ui.md](subsystems/apps_web-ui.md) |
| **Build Logic** | Подсистема `build-logic` с ключевыми точками входа: build-logic/build.gradle.kts | `build-logic/build.gradle.kts` | [subsystems/build-logic.md](subsystems/build-logic.md) |
| **Design** | Модуль и компоненты директории `design` (3 файлов) | `-` | [subsystems/design.md](subsystems/design.md) |
| **Client** | Подсистема `libs/client` с ключевыми точками входа: libs/client/build.gradle.kts | `libs/client/build.gradle.kts` | [subsystems/libs_client.md](subsystems/libs_client.md) |
| **Core** | Подсистема `libs/core` с ключевыми точками входа: libs/core/build.gradle.kts | `libs/core/build.gradle.kts` | [subsystems/libs_core.md](subsystems/libs_core.md) |
| **Core CommonMain Kotlin Dev Mimir Core** | Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core` (15 файлов) | `-` | [subsystems/libs_core_commonmain_kotlin_dev_mimir_core.md](subsystems/libs_core_commonmain_kotlin_dev_mimir_core.md) |
| **Core CommonMain Kotlin Dev Mimir Core Crypto** | Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов) | `-` | [subsystems/libs_core_commonmain_kotlin_dev_mimir_core_crypto.md](subsystems/libs_core_commonmain_kotlin_dev_mimir_core_crypto.md) |
| **Core CommonMain Kotlin Dev Mimir Core Sync** | Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/sync` (13 файлов) | `-` | [subsystems/libs_core_commonmain_kotlin_dev_mimir_core_sync.md](subsystems/libs_core_commonmain_kotlin_dev_mimir_core_sync.md) |
| **Drive** | Подсистема `libs/drive` с ключевыми точками входа: libs/drive/build.gradle.kts | `libs/drive/build.gradle.kts` | [subsystems/libs_drive.md](subsystems/libs_drive.md) |
| **Runtime** | Подсистема `libs/runtime` с ключевыми точками входа: libs/runtime/build.gradle.kts | `libs/runtime/build.gradle.kts` | [subsystems/libs_runtime.md](subsystems/libs_runtime.md) |
| **Runtime CommonMain Kotlin Dev Mimir Runtime** | Модуль и компоненты директории `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime` (15 файлов) | `-` | [subsystems/libs_runtime_commonmain_kotlin_dev_mimir_runtime.md](subsystems/libs_runtime_commonmain_kotlin_dev_mimir_runtime.md) |
| **Runtime CommonMain Kotlin Dev Mimir Runtime Update** | Модуль и компоненты директории `libs/runtime/src/commonMain/kotlin/dev/mimir/runtime/update` (6 файлов) | `-` | [subsystems/libs_runtime_commonmain_kotlin_dev_mimir_runtime_update.md](subsystems/libs_runtime_commonmain_kotlin_dev_mimir_runtime_update.md) |
| **Ui** | Подсистема `libs/ui` с ключевыми точками входа: libs/ui/build.gradle.kts | `libs/ui/build.gradle.kts` | [subsystems/libs_ui.md](subsystems/libs_ui.md) |
| **Ui CommonMain Kotlin Dev Mimir Ui** | Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов) | `-` | [subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui.md](subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui.md) |
| **Ui CommonMain Kotlin Dev Mimir Ui Screens** | Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens` (14 файлов) | `-` | [subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui_screens.md](subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui_screens.md) |
| **Packaging** | Модуль и компоненты директории `packaging` (2 файлов) | `-` | [subsystems/packaging.md](subsystems/packaging.md) |
| **Scripts** | Модуль и компоненты директории `scripts` (15 файлов) | `-` | [subsystems/scripts.md](subsystems/scripts.md) |
| **Scripts Win Vm** | Модуль и компоненты директории `scripts/win-vm` (6 файлов) | `-` | [subsystems/scripts_win-vm.md](subsystems/scripts_win-vm.md) |
| **Scripts Win Vm Tests** | Тесты для `scripts_win-vm` | `-` | [subsystems/scripts_win-vm_tests.md](subsystems/scripts_win-vm_tests.md) |

## 📋 Инварианты и правила проекта
- Код форматируется согласно Kotlin Coding Conventions (ktlint).
- Предпочитать неизменяемые структуры данных (`val`, immutable collections).
