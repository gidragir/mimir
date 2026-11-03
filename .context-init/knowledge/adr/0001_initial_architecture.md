# ADR 0001: Начальная архитектура и топология mimir

- **Статус:** Принято
- **Дата:** 2026-11-02
- **Стек:** Kotlin (Gradle)

## Контекст
Инициализация кодовой базы `mimir`. Для обеспечения надежной автономной навигации ИИ-ассистентов зафиксирована трехуровневая модель контекста (OpenViking L0-L2).

## Архитектурные границы подсистем
- **App Android** (`app-android`): Подсистема `app-android` с ключевыми точками входа: app-android/build.gradle.kts, app-android/package.json
- **App Desktop** (`app-desktop`): Подсистема `app-desktop` с ключевыми точками входа: app-desktop/build.gradle.kts, app-desktop/package.json
- **Browser Extension** (`browser-extension`): Подсистема `browser-extension` с ключевыми точками входа: browser-extension/build.gradle.kts, browser-extension/package.json
- **Build Logic** (`build-logic`): Подсистема `build-logic` с ключевыми точками входа: build-logic/build.gradle.kts
- **Client** (`client`): Подсистема `client` с ключевыми точками входа: client/build.gradle.kts, client/package.json
- **Core** (`core`): Подсистема `core` с ключевыми точками входа: core/build.gradle.kts, core/package.json
- **Core CommonMain Kotlin Dev Mimir Core** (`core_commonmain_kotlin_dev_mimir_core`): Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core` (15 файлов)
- **Core CommonMain Kotlin Dev Mimir Core Crypto** (`core_commonmain_kotlin_dev_mimir_core_crypto`): Модуль и компоненты директории `core/src/commonMain/kotlin/dev/mimir/core/crypto` (15 файлов)
- **Daemon** (`daemon`): Подсистема `daemon` с ключевыми точками входа: daemon/build.gradle.kts, daemon/package.json
- **Design** (`design`): Модуль и компоненты директории `design` (1 файлов)
- **Docs** (`docs`): Модуль и компоненты директории `docs` (15 файлов)
- **Drive** (`drive`): Подсистема `drive` с ключевыми точками входа: drive/build.gradle.kts, drive/package.json
- **Obsidian Plugin** (`obsidian-plugin`): Подсистема `obsidian-plugin` с ключевыми точками входа: obsidian-plugin/build.gradle.kts, obsidian-plugin/package.json
- **Packaging** (`packaging`): Модуль и компоненты директории `packaging` (1 файлов)
- **Runtime** (`runtime`): Подсистема `runtime` с ключевыми точками входа: runtime/build.gradle.kts, runtime/package.json
- **Scripts** (`scripts`): Модуль и компоненты директории `scripts` (4 файлов)
- **Ui** (`ui`): Подсистема `ui` с ключевыми точками входа: ui/build.gradle.kts, ui/package.json
- **Ui CommonMain** (`ui_commonmain`): Модуль и компоненты директории `ui/src/commonMain` (15 файлов)
- **Web Ui** (`web-ui`): Подсистема `web-ui` с ключевыми точками входа: web-ui/build.gradle.kts, web-ui/package.json


## Решение
1. `L0_index.json` и `L0_index.md` служат единой точкой входа (Mental Map) с минимальным потреблением токенов.
2. Каждая подсистема изолирована в карточке L1 (`subsystems/*.md`) с перечислением точек входа и сигнатур.
3. Справочник команд L2 (`cli/commands.md`) фиксирует фактические команды сборки и верификации.

## Последствия
- Устраняется необходимость рекурсивного сканирования всего репозитория при каждом промпте.
- Агенты обращаются к точечным карточкам L1 по мере необходимости.
