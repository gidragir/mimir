# ADR 0001: Начальная архитектура и топология mimir

- **Статус:** Принято
- **Дата:** 2026-11-05
- **Стек:** Kotlin (Gradle)

## Контекст
Инициализация кодовой базы `mimir`. Для обеспечения надежной автономной навигации ИИ-ассистентов зафиксирована трехуровневая модель контекста (OpenViking L0-L2).

## Архитектурные границы подсистем
- **Android** (`apps_android`): Подсистема `apps/android` с ключевыми точками входа: apps/android/build.gradle.kts
- **Browser Extension** (`apps_browser-extension`): Подсистема `apps/browser-extension` с ключевыми точками входа: apps/browser-extension/build.gradle.kts
- **Browser Extension JsMain** (`apps_browser-extension_jsmain`): Модуль и компоненты директории `apps/browser-extension/src/jsMain` (3 файлов)
- **Browser Extension JsMain Kotlin Dev Mimir Extension** (`apps_browser-extension_jsmain_kotlin_dev_mimir_extension`): Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension` (8 файлов)
- **Browser Extension JsMain Kotlin Dev Mimir Extension Ui** (`apps_browser-extension_jsmain_kotlin_dev_mimir_extension_ui`): Модуль и компоненты директории `apps/browser-extension/src/jsMain/kotlin/dev/mimir/extension/ui` (8 файлов)
- **Daemon** (`apps_daemon`): Подсистема `apps/daemon` с ключевыми точками входа: apps/daemon/build.gradle.kts
- **Desktop** (`apps_desktop`): Подсистема `apps/desktop` с ключевыми точками входа: apps/desktop/build.gradle.kts
- **Obsidian Plugin** (`apps_obsidian-plugin`): Подсистема `apps/obsidian-plugin` с ключевыми точками входа: apps/obsidian-plugin/build.gradle.kts
- **Web Ui** (`apps_web-ui`): Подсистема `apps/web-ui` с ключевыми точками входа: apps/web-ui/build.gradle.kts
- **Build Logic** (`build-logic`): Подсистема `build-logic` с ключевыми точками входа: build-logic/build.gradle.kts
- **Design** (`design`): Модуль и компоненты директории `design` (1 файлов)
- **Client** (`libs_client`): Подсистема `libs/client` с ключевыми точками входа: libs/client/build.gradle.kts
- **Core** (`libs_core`): Подсистема `libs/core` с ключевыми точками входа: libs/core/build.gradle.kts
- **Core CommonMain Kotlin Dev Mimir Core** (`libs_core_commonmain_kotlin_dev_mimir_core`): Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core` (15 файлов)
- **Core CommonMain Kotlin Dev Mimir Core Crypto** (`libs_core_commonmain_kotlin_dev_mimir_core_crypto`): Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов)
- **Core CommonMain Kotlin Dev Mimir Core Sync** (`libs_core_commonmain_kotlin_dev_mimir_core_sync`): Модуль и компоненты директории `libs/core/src/commonMain/kotlin/dev/mimir/core/sync` (13 файлов)
- **Drive** (`libs_drive`): Подсистема `libs/drive` с ключевыми точками входа: libs/drive/build.gradle.kts
- **Runtime** (`libs_runtime`): Подсистема `libs/runtime` с ключевыми точками входа: libs/runtime/build.gradle.kts
- **Runtime CommonMain** (`libs_runtime_commonmain`): Модуль и компоненты директории `libs/runtime/src/commonMain` (15 файлов)
- **Ui** (`libs_ui`): Подсистема `libs/ui` с ключевыми точками входа: libs/ui/build.gradle.kts
- **Ui CommonMain Kotlin Dev Mimir Ui** (`libs_ui_commonmain_kotlin_dev_mimir_ui`): Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui` (15 файлов)
- **Ui CommonMain Kotlin Dev Mimir Ui Screens** (`libs_ui_commonmain_kotlin_dev_mimir_ui_screens`): Модуль и компоненты директории `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens` (12 файлов)
- **Packaging** (`packaging`): Модуль и компоненты директории `packaging` (1 файлов)
- **Scripts** (`scripts`): Модуль и компоненты директории `scripts` (2 файлов)


## Решение
1. `L0_index.json` и `L0_index.md` служат единой точкой входа (Mental Map) с минимальным потреблением токенов.
2. Каждая подсистема изолирована в карточке L1 (`subsystems/*.md`) с перечислением точек входа и сигнатур.
3. Справочник команд L2 (`cli/commands.md`) фиксирует фактические команды сборки и верификации.

## Последствия
- Устраняется необходимость рекурсивного сканирования всего репозитория при каждом промпте.
- Агенты обращаются к точечным карточкам L1 по мере необходимости.
