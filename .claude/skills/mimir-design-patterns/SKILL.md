---
name: mimir-design-patterns
description: Use when adding a class, feature, service, screen or module to Mimir, deciding where new code goes, or when about to add a when/if over platform or cloud type, an `is ConcreteType` check, a global singleton, a copy of existing logic, or grow a file past ~300 lines (DaemonRuntime, SetupWizardScreen).
---

# Паттерны проектирования Mimir

## Суть

Mimir — многоплатформенный продукт с одним движком и несколькими облаками и интерфейсами. Новый код **встраивается в один из паттернов ниже** по образцу эталонного класса; новый паттерн появляется только записью в спеке (`Ruling:`).

## Каталог

| Нужно | Паттерн | Эталон в коде |
|---|---|---|
| Новая внешняя зависимость (облако, ФС, секреты, сеть, часы) | Порт и адаптер: интерфейс в `core/api`, реализация снаружи | `RemoteStorage` ↔ `GoogleDriveStorage`; `LocalVault` ↔ `FileSystemLocalVault` |
| Сквозное поведение поверх порта (шифрование, кэш, лимиты, метрики, повторы) | Декоратор, адаптер не трогается | `EncryptedRemoteStorage(inner, cipher)` |
| Подключаемые варианты (облака, способы входа) | Реестр + фабрика | `RemoteStorageRegistry`, `RemoteStorageProvider.create` |
| Поведение зависит от возможностей | Стратегия, выбор по флагам, не по типу | `StorageCapabilities(changeFeed)`, `ConflictResolver` |
| Новое поведение синхронизации | План → исполнение: чистое решение отдельно от эффектов | `SyncPlanBuilder` → `SyncAction` → `DefaultSyncEngine.apply` |
| Состояния и результаты | `sealed` + исчерпывающий `when` | `SyncStatus`, `SyncPhase`, `SyncAction`, `UiBrowserPoll` |
| Наблюдаемое состояние | `StateFlow`, однонаправленный поток данных: экран читает состояние, события — `suspend`-методы модели | `SyncEngine.progress`, `MimirUiModel` |
| Один бэкенд — много клиентов | Фасад + удалённый заместитель + адаптер к UI | `DaemonService` ← `DaemonRuntime` / `HttpDaemonClient`; `DaemonServiceUiModel` → `MimirUiModel` |
| Платформенный сервис в Compose | CompositionLocal + фабрика | `LocalFolderPicker` + `defaultFolderPicker()` |
| Сборка графа объектов | Composition root, внедрение через конструктор | `DaemonRuntime`, `app-desktop/Main.kt`, `DaemonServiceFactory` |
| Тестируемые побочные эффекты | Параметры-функции со значениями по умолчанию | `SystemBrowserOpener(osName, launch, browse)` |
| Хранение состояния | Репозиторий + атомарная запись | `SyncStateStore` → `JsonSyncStateStore`, `writeFileAtomically` (`AtomicFiles.kt`) |
| Тестовый двойник порта | In-memory фейк в `core/testing` | `InMemoryRemoteStorage`, `FakeMimirUiModel` |
| Платформенный примитив в `core` | минимальный `expect/actual` | источник случайности, символьные ссылки |

## Рецепт

1. Найти строку каталога, открыть эталон, повторить его форму (имена, где интерфейс, где реализация, где тест).
2. В спеке/плане и отчёте — пункт **«Дизайн»**: паттерн, эталонный класс, новые типы и их модули.
3. Порт получает фейк и общий контрактный тест, прогоняемый и для фейка, и для настоящей реализации.
4. Класс с двумя причинами для изменения или длиннее ~300 строк делится до того, как в него добавляется новое (`DaemonRuntime`, `SetupWizardScreen` — уже на границе: новое выносить в отдельные классы/composable-файлы).

## Красные флаги

- `when (platform)`, `is GoogleDriveStorage`, `if (cloud == "drive")` в `core`/`ui`.
- `object`-синглтон с состоянием, сервис-локатор, `GlobalScope`.
- Логика синхронизации в `daemon`, `ui` или точке входа (спека: единый бэкенд).
- Скопированный кусок из соседнего класса вместо общего типа.
- Новый интерфейс без фейка и без теста контракта.
