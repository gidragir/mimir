# 🗺 Карта архитектуры: mimir (L0)

> **Стек:** Kotlin (Gradle) | **Обновлено:** 2026-11-03
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
| **App Android** | Подсистема `app-android` с ключевыми точками входа: app-android/package.json | `app-android/package.json` | [subsystems/app-android.md](subsystems/app-android.md) |
| **App Desktop** | Подсистема `app-desktop` с ключевыми точками входа: app-desktop/package.json | `app-desktop/package.json` | [subsystems/app-desktop.md](subsystems/app-desktop.md) |
| **Browser Extension** | Подсистема `browser-extension` с ключевыми точками входа: browser-extension/package.json | `browser-extension/package.json` | [subsystems/browser-extension.md](subsystems/browser-extension.md) |
| **Build Logic** | Модуль и компоненты директории `build-logic` (5 файлов) | `-` | [subsystems/build-logic.md](subsystems/build-logic.md) |
| **Client** | Подсистема `client` с ключевыми точками входа: client/package.json | `client/package.json` | [subsystems/client.md](subsystems/client.md) |
| **Core** | Подсистема `core` с ключевыми точками входа: core/package.json | `core/package.json` | [subsystems/core.md](subsystems/core.md) |
| **Core CommonMain Kotlin Dev Mimir Core** | Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core` (15 файлов) | `-` | [subsystems/core_commonmain_kotlin_dev_mimir_core.md](subsystems/core_commonmain_kotlin_dev_mimir_core.md) |
| **Core CommonMain Kotlin Dev Mimir Core Crypto** | Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов) | `-` | [subsystems/core_commonmain_kotlin_dev_mimir_core_crypto.md](subsystems/core_commonmain_kotlin_dev_mimir_core_crypto.md) |
| **Daemon** | Подсистема `daemon` с ключевыми точками входа: daemon/package.json | `daemon/package.json` | [subsystems/daemon.md](subsystems/daemon.md) |
| **Design** | Модуль и компоненты директории `design` (1 файлов) | `-` | [subsystems/design.md](subsystems/design.md) |
| **Docs** | Модуль и компоненты директории `docs` (15 файлов) | `-` | [subsystems/docs.md](subsystems/docs.md) |
| **Drive** | Подсистема `drive` с ключевыми точками входа: drive/package.json | `drive/package.json` | [subsystems/drive.md](subsystems/drive.md) |
| **Gradle** | Модуль и компоненты директории `gradle` (1 файлов) | `-` | [subsystems/gradle.md](subsystems/gradle.md) |
| **Obsidian Plugin** | Подсистема `obsidian-plugin` с ключевыми точками входа: obsidian-plugin/package.json | `obsidian-plugin/package.json` | [subsystems/obsidian-plugin.md](subsystems/obsidian-plugin.md) |
| **Packaging** | Модуль и компоненты директории `packaging` (1 файлов) | `-` | [subsystems/packaging.md](subsystems/packaging.md) |
| **Runtime** | Подсистема `runtime` с ключевыми точками входа: runtime/package.json | `runtime/package.json` | [subsystems/runtime.md](subsystems/runtime.md) |
| **Scripts** | Модуль и компоненты директории `scripts` (4 файлов) | `-` | [subsystems/scripts.md](subsystems/scripts.md) |
| **Ui** | Подсистема `ui` с ключевыми точками входа: ui/package.json | `ui/package.json` | [subsystems/ui.md](subsystems/ui.md) |
| **Ui CommonMain Kotlin Dev Mimir Ui** | Модуль и компоненты директории `ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов) | `-` | [subsystems/ui_commonmain_kotlin_dev_mimir_ui.md](subsystems/ui_commonmain_kotlin_dev_mimir_ui.md) |
| **Ui CommonMain Kotlin Dev Mimir Ui Screens** | Модуль и компоненты директории `ui/src/commonMain/kotlin/dev/mimir/ui/screens` (12 файлов) | `-` | [subsystems/ui_commonmain_kotlin_dev_mimir_ui_screens.md](subsystems/ui_commonmain_kotlin_dev_mimir_ui_screens.md) |
| **Web Ui** | Подсистема `web-ui` с ключевыми точками входа: web-ui/package.json | `web-ui/package.json` | [subsystems/web-ui.md](subsystems/web-ui.md) |

## 📋 Инварианты и правила проекта
- Код форматируется согласно Kotlin Coding Conventions (ktlint).
- Предпочитать неизменяемые структуры данных (`val`, immutable collections).
