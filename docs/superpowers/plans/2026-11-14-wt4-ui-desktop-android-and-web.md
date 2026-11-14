# Оптимизация и рефакторинг :libs:ui, :apps:desktop, :apps:android и :apps:web-ui (после слияния WT1 + WT2) — Implementation Plan

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ СКИЛЛ: `superpowers:using-superpowers` → `superpowers:executing-plans` → `superpowers:test-driven-development` → `superpowers:verification-before-completion`. Выполняется в изолированном воркспейсе (`Workspace: "share"` в Antigravity или `.worktrees/wt-ui-desktop-android-web` на ветке `refactor/ui-desktop-android-web`) поверх `main` с уже влитыми WT1 и WT2. Шаги отмечаются чекбоксами (`- [ ]`). Прогресс фиксировать в `.superpowers/sdd/2026-11-14-wt4-ui-desktop-android-and-web/progress.md`.

**Goal:** Изолировать рекомпозиции Compose на экранах `StatusScreen` и `TrayPopoverScreen`, отключить подписки скрытого `TrayPopoverWindow`, устранить холостой 500-мс поллинг в `DaemonServiceUiModel`, оптимизировать уведомления Android (`MimirApplication`), декомпозировать крупные экраны, перевести тесты на общие фейки из `:libs:core` и подключить `:apps:web-ui` к `HttpDaemonClient`.

**Architecture:** Все изменения строго ограничены `libs/ui/src/**`, `apps/desktop/src/**`, `apps/android/src/**` и `apps/web-ui/**`. На уровне `:libs:ui` чтение быстро меняющихся `progress` и секундного таймера `now` изолируется во вложенных `@Composable` функциях; карточки статуса выносятся в `StatusSyncCards.kt`, а шаг Device Code — в `DeviceCodeStep.kt`. В `:apps:web-ui` заглушка `StubSyncEngine` заменяется на `DaemonServiceUiModel` поверх `HttpDaemonClient`.

**Tech Stack:** Compose Multiplatform 1.12.2 (Desktop JVM, Android, WasmJs), Material 3, Android WorkManager & Activity Compose, Ktor Client 3.6.0.

**Spec:** `docs/superpowers/specs/2026-11-02-unified-ui-components-design.md`, `docs/superpowers/specs/2026-11-03-web-ui-docker-design.md`, `docs/superpowers/specs/2026-10-27-mimir-android-design.md`

**Worktree / Workspace:** Antigravity `Workspace: "share"` или `.worktrees/wt-ui-desktop-android-web` (ветка `refactor/ui-desktop-android-web`)

## Global Constraints

- Строгая граница файлов: изменять **только** `libs/ui/src/**`, `apps/desktop/src/**`, `apps/android/src/**`, `apps/web-ui/**`. Не трогать `libs/core/**`, `libs/drive/**`, `libs/runtime/**`, `libs/client/**`, `apps/daemon/**`, `apps/obsidian-plugin/**`, `apps/browser-extension/**`.
- В `libs/ui/commonMain` запрещены ссылки на `java.awt`, `System.getenv`, `os.name`, Android SDK.
- Все цвета, отступы и радиусы берутся строго из `MimirTokens`.
- При запуске `./gradlew` передавать `--max-workers=4` (для защиты лимита 16 ГБ ОЗУ при параллельной работе с WT3 и WT5), а в конце всей работы выполнить `rtk ./gradlew --stop`.
- Все терминальные команды выполняются строго с префиксом `rtk`; навигация по коду — через `rtk context-init pack` и `rtk rg`.

## Инварианты Mimir

1. **Платформы:**
   - **Linux / Windows / macOS (`:apps:desktop`):** скрытое окно трея `TrayPopoverWindow` (`visible = false`) не держит в композиции `TrayPopoverScreen` и не подписывается на 8 `StateFlow`; `DaemonServiceUiModel` не будит корутину каждые 500 мс в состоянии `Idle`.
   - **Android (`:apps:android`):** `MimirApplication.workScheduler` кэшируется через `by lazy`; 4 потока состояния для системного уведомления объединяются через `combine(...).distinctUntilChanged()`.
   - **Docker / Браузер (`:apps:web-ui`):** Wasm-клиент подключается к демону по `window.location.origin` через `HttpDaemonClient` вместо падения на `StubSyncEngine`.
2. **Дизайн:**
   - Сохраняется однонаправленный поток данных (UDF) через `MimirUiModel`.
   - Декомпозиция `StatusScreen.kt` (→ `StatusSyncCards.kt`) и `GoogleStep.kt` (→ `DeviceCodeStep.kt`) удерживает размер файлов ниже 250 строк.
   - Использование общих `TestClock` и `FakeUpdateHttpClient` из `dev.mimir.core.testing` (введённых в WT2).
3. **IO:**
   - Устранены лишние обращения к `SharedPreferences` в `StatusNotifier` на Android и 3 лишних старта `embeddedServer(CIO)` в `DaemonServiceFactoryTest`.
4. **UX:**
   - Переход с устаревшего `LocalClipboardManager` на `LocalClipboard` в `GoogleStep.kt`; отсутствие фризов кнопок и баннеров `StatusScreen` при интенсивном потоке `SyncProgress`.

## Review Focus

1. **Рекомпозиция `StatusScreen` и `TrayPopoverScreen`:** вынос `progressFlow` и секундного тикера `produceState` во вложенный `@Composable` сохраняет прохождение всех тестов `StatusScreenTest` и `TrayPopoverScreenTest`.
2. **Повторное открытие `TrayPopoverWindow`:** при переключении `visible` `false -> true -> false -> true` `TrayPopoverScreen` корректно монтируется и отображает актуальное состояние `MimirUiModel`.
3. **Копирование кода устройства и ссылки через `LocalClipboard`:** кнопки копирования в `InteractiveLoginStep` и `DeviceCodeStep` работают во всех desktop UI-тестах (`SetupWizardScreenTest`).
4. **Компиляция WasmJs для `:libs:ui` и `:apps:web-ui`:** подключение `HttpDaemonClient` в `:apps:web-ui` собирается задачей `:apps:web-ui:wasmJsBrowserDistribution`.
5. **Унификация фейков:** дубли `FakeStorageAccess` (`AccessStepTest`, `StorageAccessBannerTest`), `FakeUpdateHttpClient` (`WindowsMsiApplierTest`) и `TestClock` (`TrayPopoverScreenTest`, `GisTokenSourceTest`) удалены в пользу единых классов.

## Структура файлов

```
libs/ui/src/commonMain/kotlin/dev/mimir/ui/
  model/DaemonServiceUiModel.kt              (изм.: реактивное обновление при смене status/progress без холостого 500-мс цикла в Idle)
  screens/StatusScreen.kt                    (изм.: <200 строк, изоляция чтения progress и таймера now)
  screens/StatusSyncCards.kt                 (созд.: вынос ProgressBlock, CompletedBlock, ProblemsBlock, Details)
  screens/TrayPopoverScreen.kt               (изм.: декомпозиция ModelContent на PopoverVaultSummary, PopoverBanners, PopoverActions)
  screens/setup/GoogleStep.kt                (изм.: <200 строк, переход на LocalClipboard)
  screens/setup/DeviceCodeStep.kt            (созд.: вынос DeviceCodeStep из GoogleStep.kt)
libs/ui/src/desktopTest/kotlin/dev/mimir/ui/
  FakeStorageAccess.kt                       (созд.: единый фейк вместо 4 дублей в AccessStepTest, StorageAccessBannerTest, SetupWizardScreenTest)
  TrayPopoverScreenTest.kt                   (изм.: использование TestClock из dev.mimir.core.testing)
apps/desktop/src/main/kotlin/dev/mimir/desktop/
  tray/TrayPopoverWindow.kt                  (изм.: рендеринг TrayPopoverScreen только при visible == true)
apps/desktop/src/test/kotlin/dev/mimir/desktop/
  DaemonServiceFactoryTest.kt                (изм.: проверка выбора UpdateApplier без поднятия 3 лишних embeddedServer)
  update/WindowsMsiApplierTest.kt            (изм.: использование FakeUpdateHttpClient из dev.mimir.core.testing)
apps/android/src/main/kotlin/dev/mimir/android/
  MimirApplication.kt                        (изм.: workScheduler by lazy, combine 4 потоков в observeSyncForNotifications)
  MainViewModel.kt                           (изм.: реактивный vaultPathFlow, удаление мёртвого && false)
apps/android/src/test/kotlin/dev/mimir/android/
  auth/GisTokenSourceTest.kt                 (изм.: использование TestClock из dev.mimir.core.testing)
  work/FakeSyncRunner.kt                     (созд.: единый фейк для MimirSyncWorkerTest и VaultChangeWorkerTest)
apps/web-ui/
  build.gradle.kts                           (изм.: зависимость на :libs:client и ktor-client-core)
  src/wasmJsMain/kotlin/dev/mimir/web/Main.kt (изм.: подключение HttpDaemonClient + DaemonServiceUiModel вместо StubSyncEngine)
```

---

### Task 1: Декомпозиция и оптимизация рекомпозиций в `:libs:ui` (`StatusScreen`, `StatusSyncCards`, `TrayPopoverScreen`, `GoogleStep`, `DeviceCodeStep`, `DaemonServiceUiModel`)

**Files:**
- Create: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusSyncCards.kt`
- Create: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/DeviceCodeStep.kt`
- Create: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/FakeStorageAccess.kt`
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt`
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/TrayPopoverScreen.kt`
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/GoogleStep.kt`
- Modify: `libs/ui/src/commonMain/kotlin/dev/mimir/ui/model/DaemonServiceUiModel.kt`
- Modify: `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/AccessStepTest.kt`, `StorageAccessBannerTest.kt`, `SetupWizardScreenTest.kt`, `TrayPopoverScreenTest.kt`

**Interfaces:**
- Consumes: `dev.mimir.core.testing.TestClock` из `:libs:core` (WT2).
- Produces:
  - `internal fun StatusSyncProgressSection(...)` в `StatusSyncCards.kt`
  - `@Composable internal fun DeviceCodeStep(...)` в `DeviceCodeStep.kt`
  - `internal class FakeStorageAccess(...) : StorageAccess` в `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/FakeStorageAccess.kt`

- [ ] **Step 1: Вынести единый `FakeStorageAccess` в `libs/ui/src/desktopTest/kotlin/dev/mimir/ui/FakeStorageAccess.kt` и перевести `TrayPopoverScreenTest.kt:35` на `dev.mimir.core.testing.TestClock`**

Run: `rtk ./gradlew :libs:ui:desktopTest --max-workers=4`
Expected: PASS.

- [ ] **Step 2: Вынести `StatusSyncCards.kt` из `StatusScreen.kt` и `DeviceCodeStep.kt` из `GoogleStep.kt`, изолировать подписку на `progress` и `produceState(now)`, обновить `DaemonServiceUiModel`**

1. В `StatusScreen.kt` и `StatusSyncCards.kt`: перенести `ProgressBlock`, `CompletedBlock`, `ProblemsBlock`, `Details` в `StatusSyncCards.kt`; передавать `model.progress` внутрь `StatusSyncProgressSection`, где вызывается `collectAsState()` и крутится секундный `produceState`.
2. В `TrayPopoverScreen.kt`: разбить `ModelContent` на `PopoverVaultSummary`, `PopoverBanners` и `PopoverActions`, изолировав чтение `model.progress`.
3. В `GoogleStep.kt`: вынести `DeviceCodeStep` в `DeviceCodeStep.kt`; заменить устаревший `LocalClipboardManager` на `LocalClipboard` без `@Suppress("DEPRECATION")`.
4. В `DaemonServiceUiModel.kt`: вызывать `refresh()` реактивно при эмиссии `service.status` / `service.progress` и после каждого mutating-метода.

- [ ] **Step 3: Проверить все тесты `:libs:ui:desktopTest` и компиляцию WasmJs `:libs:ui:compileKotlinWasmJs`**

Run: `rtk ./gradlew :libs:ui:desktopTest :libs:ui:compileKotlinWasmJs --max-workers=4`
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add libs/ui/
rtk oco -y
```

---

### Task 2: Оптимизация `:apps:desktop` (`TrayPopoverWindow`, `DaemonServiceFactoryTest`, `WindowsMsiApplierTest`) и `:apps:android` (`MimirApplication`, `MainViewModel`, `FakeSyncRunner`, `GisTokenSourceTest`)

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/mimir/desktop/tray/TrayPopoverWindow.kt:58-82`
- Modify: `apps/desktop/src/test/kotlin/dev/mimir/desktop/DaemonServiceFactoryTest.kt:45-70`
- Modify: `apps/desktop/src/test/kotlin/dev/mimir/desktop/update/WindowsMsiApplierTest.kt:31`
- Modify: `apps/android/src/main/kotlin/dev/mimir/android/MimirApplication.kt:69-142`
- Modify: `apps/android/src/main/kotlin/dev/mimir/android/MainViewModel.kt:25-39`
- Modify: `apps/android/src/test/kotlin/dev/mimir/android/auth/GisTokenSourceTest.kt:19`
- Create: `apps/android/src/test/kotlin/dev/mimir/android/work/FakeSyncRunner.kt`
- Modify: `apps/android/src/test/kotlin/dev/mimir/android/work/MimirSyncWorkerTest.kt`, `VaultChangeWorkerTest.kt`

**Interfaces:**
- Consumes: `dev.mimir.core.testing.TestClock`, `dev.mimir.core.testing.FakeUpdateHttpClient` из `:libs:core` (WT2).

- [ ] **Step 1: В `TrayPopoverWindow.kt` обернуть `TrayPopoverScreen` в `if (visible)`, в `WindowsMsiApplierTest.kt` использовать `FakeUpdateHttpClient` из `:libs:core`, а в `DaemonServiceFactoryTest.kt` убрать 3 лишних старта `embeddedServer(CIO)`**

Run: `rtk ./gradlew :apps:desktop:test --max-workers=4`
Expected: PASS.

- [ ] **Step 2: Оптимизировать `MimirApplication.kt`, `MainViewModel.kt`, перевести `GisTokenSourceTest.kt` на `TestClock` и объединить `FakeSyncRunner` в `:apps:android`**

1. В `MimirApplication.kt`: сделать `val workScheduler by lazy { ... }` и объединить 4 корутины в `observeSyncForNotifications()` через `combine(...).distinctUntilChanged()`.
2. В `MainViewModel.kt:25-39`: использовать `runtime.vaultPathFlow` и удалить `&& false`.
3. В `GisTokenSourceTest.kt:19` использовать `dev.mimir.core.testing.TestClock`.
4. Объединить `FakeSyncRunner` и `RecordingSyncRunner` в `apps/android/src/test/kotlin/dev/mimir/android/work/FakeSyncRunner.kt`.

- [ ] **Step 3: Прогнать тесты `:apps:desktop:test` и `:apps:android:testDebugUnitTest`**

Run: `rtk ./gradlew :apps:desktop:test :apps:android:testDebugUnitTest --max-workers=4`
Expected: PASS.

- [ ] **Step 4: Закоммитить изменения**

```bash
rtk git add apps/desktop/ apps/android/
rtk oco -y
```

---

### Task 3: Подключение `:apps:web-ui` к `HttpDaemonClient` вместо `StubSyncEngine`

**Files:**
- Modify: `apps/web-ui/build.gradle.kts`
- Modify: `apps/web-ui/src/wasmJsMain/kotlin/dev/mimir/web/Main.kt`

**Interfaces:**
- Consumes: `HttpDaemonClient`, `DaemonServiceUiModel` из `:libs:client` и `:libs:ui`.

- [ ] **Step 1: Добавить `:libs:client` и `ktor-client-core` в `apps/web-ui/build.gradle.kts` и подключить `HttpDaemonClient` в `apps/web-ui/src/wasmJsMain/kotlin/dev/mimir/web/Main.kt`**

В `apps/web-ui/src/wasmJsMain/kotlin/dev/mimir/web/Main.kt`:
- Определять `baseUrl = window.location.origin` и опциональный токен из `sessionStorage` / query-параметра.
- Создавать `HttpDaemonClient(baseUrl = baseUrl, token = token, scope = scope)` и передавать `DaemonServiceUiModel(client, scope)` в `MimirApp(model = model)`.

- [ ] **Step 2: Собрать production Wasm-дистрибутив `:apps:web-ui`, проверить дрейф `context-init` и остановить Gradle Daemon**

Run: `rtk ./gradlew :apps:web-ui:wasmJsBrowserDistribution --max-workers=4 && rtk context-init check --compact && rtk ./gradlew --stop`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Закоммитить изменения**

```bash
rtk git add apps/web-ui/
rtk oco -y
```
