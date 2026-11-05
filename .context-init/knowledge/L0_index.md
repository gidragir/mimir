# 🗺 Карта архитектуры: mimir (L0)

> **Стек:** Kotlin (Gradle) | **Обновлено:** 2026-11-05
> **Описание:** Один открытый инструмент синхронизации Obsidian-хранилища на всех платформах (Linux, Windows, Android). Он ощущается как Google Drive Desktop: один раз указал папку vault и пароль, дальше всё синхронизируется само.

## 🚪 Точки входа (Root Entrypoints)
- `package.json`
- `mise.toml`

## ⚡️ Быстрые команды
```bash
# Сборка
rtk ./gradlew build

# Тесты
rtk ./gradlew test

# Запуск
rtk ./gradlew run

```

## 🧩 Каталог подсистем (L1)

| Подсистема | Роль / Назначение | Точки входа | L1 Карточка |
| :--- | :--- | :--- | :--- |
| **Android** | Модуль и компоненты директории `apps/android` (15 файлов) | `-` | [subsystems/apps_android.md](subsystems/apps_android.md) |
| **Browser Extension** | Модуль и компоненты директории `apps/browser-extension` (15 файлов) | `-` | [subsystems/apps_browser-extension.md](subsystems/apps_browser-extension.md) |
| **Daemon** | Модуль и компоненты директории `apps/daemon` (9 файлов) | `-` | [subsystems/apps_daemon.md](subsystems/apps_daemon.md) |
| **Desktop** | Модуль и компоненты директории `apps/desktop` (15 файлов) | `-` | [subsystems/apps_desktop.md](subsystems/apps_desktop.md) |
| **Obsidian Plugin** | Модуль и компоненты директории `apps/obsidian-plugin` (8 файлов) | `-` | [subsystems/apps_obsidian-plugin.md](subsystems/apps_obsidian-plugin.md) |
| **Web Ui** | Модуль и компоненты директории `apps/web-ui` (2 файлов) | `-` | [subsystems/apps_web-ui.md](subsystems/apps_web-ui.md) |
| **Build Logic** | Модуль и компоненты директории `build-logic` (5 файлов) | `-` | [subsystems/build-logic.md](subsystems/build-logic.md) |
| **Design** | Модуль и компоненты директории `design` (1 файлов) | `-` | [subsystems/design.md](subsystems/design.md) |
| **Docs** | Модуль и компоненты директории `docs` (15 файлов) | `-` | [subsystems/docs.md](subsystems/docs.md) |
| **Gradle** | Модуль и компоненты директории `gradle` (1 файлов) | `-` | [subsystems/gradle.md](subsystems/gradle.md) |
| **Libs** | Модуль и компоненты директории `libs` (15 файлов) | `-` | [subsystems/libs.md](subsystems/libs.md) |
| **Client** | Модуль и компоненты директории `libs/client` (5 файлов) | `-` | [subsystems/libs_client.md](subsystems/libs_client.md) |
| **Libs Core** | Модуль и компоненты директории `libs/core` (15 файлов) | `-` | [subsystems/libs_core.md](subsystems/libs_core.md) |
| **Core CommonMain Kotlin Dev Mimir Core** | Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core` (15 файлов) | `-` | [subsystems/libs_core_commonmain_kotlin_dev_mimir_core.md](subsystems/libs_core_commonmain_kotlin_dev_mimir_core.md) |
| **Core CommonMain Kotlin Dev Mimir Core Crypto** | Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов) | `-` | [subsystems/libs_core_commonmain_kotlin_dev_mimir_core_crypto.md](subsystems/libs_core_commonmain_kotlin_dev_mimir_core_crypto.md) |
| **Libs Core Src** | Модуль и компоненты директории `libs/core/src` (15 файлов) | `-` | [subsystems/libs_core_src.md](subsystems/libs_core_src.md) |
| **Libs Core Src CommonMain Kotlin Dev Mimir Core** | Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core` (15 файлов) | `-` | [subsystems/libs_core_src_commonmain_kotlin_dev_mimir_core.md](subsystems/libs_core_src_commonmain_kotlin_dev_mimir_core.md) |
| **Libs Core Src CommonMain Kotlin Dev Mimir Core Crypto** | Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов) | `-` | [subsystems/libs_core_src_commonmain_kotlin_dev_mimir_core_crypto.md](subsystems/libs_core_src_commonmain_kotlin_dev_mimir_core_crypto.md) |
| **Drive** | Модуль и компоненты директории `libs/drive` (15 файлов) | `-` | [subsystems/libs_drive.md](subsystems/libs_drive.md) |
| **Libs Runtime** | Модуль и компоненты директории `libs/runtime` (15 файлов) | `-` | [subsystems/libs_runtime.md](subsystems/libs_runtime.md) |
| **Libs Ui** | Модуль и компоненты директории `libs/ui` (14 файлов) | `-` | [subsystems/libs_ui.md](subsystems/libs_ui.md) |
| **Ui CommonMain Kotlin Dev Mimir Ui** | Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов) | `-` | [subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui.md](subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui.md) |
| **Ui CommonMain Kotlin Dev Mimir Ui Screens** | Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens` (12 файлов) | `-` | [subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui_screens.md](subsystems/libs_ui_commonmain_kotlin_dev_mimir_ui_screens.md) |
| **Libs Ui Src** | Модуль и компоненты директории `libs/ui/src` (13 файлов) | `-` | [subsystems/libs_ui_src.md](subsystems/libs_ui_src.md) |
| **Libs Ui Src CommonMain Kotlin Dev Mimir Ui** | Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов) | `-` | [subsystems/libs_ui_src_commonmain_kotlin_dev_mimir_ui.md](subsystems/libs_ui_src_commonmain_kotlin_dev_mimir_ui.md) |
| **Libs Ui Src CommonMain Kotlin Dev Mimir Ui Screens** | Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens` (12 файлов) | `-` | [subsystems/libs_ui_src_commonmain_kotlin_dev_mimir_ui_screens.md](subsystems/libs_ui_src_commonmain_kotlin_dev_mimir_ui_screens.md) |
| **Packaging** | Модуль и компоненты директории `packaging` (1 файлов) | `-` | [subsystems/packaging.md](subsystems/packaging.md) |
| **Scripts** | Модуль и компоненты директории `scripts` (2 файлов) | `-` | [subsystems/scripts.md](subsystems/scripts.md) |

## 📋 Инварианты и правила проекта
- Код форматируется согласно Kotlin Coding Conventions (ktlint).
- Предпочитать неизменяемые структуры данных (`val`, immutable collections).
