# Спецификация: Исправление жизненного цикла синхронизации, фоновой сети и двойного сканирования на Android

**Дата:** 2026-11-07  
**Статус:** На согласовании  
**Область:** `:apps:android`, `:libs:runtime`, `:libs:ui`  
**Связанные документы:** `docs/superpowers/specs/2026-10-23-mimir-design.md`, `docs/superpowers/specs/2026-10-27-mimir-android-design.md`

---

## 1. Замысел и доказательная база (Intent & Live Telemetry)

При эксплуатации Mimir v0.4.0 на физическом устройстве (`SM_S928B`, Android 16 / API 36, `UID 10033`) зафиксированы три взаимосвязанных дефекта:
1. **Ложное уведомление «Нет подключения к интернету» при активных Wi-Fi и LTE.**
2. **Двойная проверка (сканирование) файлов при синхронизации существующего хранилища.**
3. **Остановка синхронизации после выключения экрана / сворачивания приложения.**

### 1.1. Подтверждённая причина дефектов 1 и 3 (срез `logcat -b crash`, `logcat -b events`, `dumpsys netpolicy`)

| Таймстамп (`11-06`) | Источник | Фактическое событие на устройстве |
| :--- | :--- | :--- |
| `23:57:30.226` | `wm_on_stop_called` | Выключение экрана во время синхронизации (`PID 10502`). `MimirAppLifecycleObserver.onStop` вызывает `rt.setInteractive(false)` $\to$ `scheduler?.stop(cancelRunner = false)`. Фоновая корутина `runner` продолжает качать поток из Google Drive в обычном процессе без `ForegroundService` и без `WorkManager`. |
| `23:57:35.210` | `dumpsys netpolicy` | Ровно через **5,0 с** после `onStop` Android переводит `UID 10033` в состояние `procState=CEM` и применяет сетевую блокировку `blocked=APP_BACKGROUND`, принудительно разрывая TCP-сокеты процесса при работающих Wi-Fi и LTE. |
| `23:57:35.220` | `NotificationManager` | Оборванное чтение chunked-ответа Ktor (`java.io.EOFException: Not enough data available` в `ByteReadChannelOperations.kt:45`) оборачивается в `RemoteUnavailableException` $\to$ `DefaultSyncEngine` переводит статус в `SyncStatus.Offline` $\to$ публикуется уведомление ID `1` **«Нет подключения к интернету»**. |
| `23:57:35.221` | `AndroidRuntime` | **Фатальный краш процесса (`am_crash` / `am_proc_died` / `am_uid_stopped: 10033`):** `DefaultSyncEngine.syncNow()` пробрасывает `RemoteUnavailableException` в `SyncScheduler.runNow()` (`runOnce(rethrow = true)`), который был вызван из `MimirAppLifecycleObserver.onStart` (`scope.launch { ... rt.syncOnce() }`) на `Dispatchers.Default` **без блока `try/catch`**. Необработанное исключение убивает процесс. |

### 1.2. Подтверждённая причина дефекта 2 (срез `dumpsys notification`)

В истории уведомлений зафиксированы парные завершения синхронизации с интервалом 1,5–6 секунд (`00:08:53.486` $\to$ `00:08:55.011`, `00:09:14.059` $\to$ `00:09:20.412`):
1. `MimirAppLifecycleObserver.onStart` последовательно вызывает `rt.initialize()` / `rt.setInteractive(true)` и сразу за ним `rt.syncOnce()`.
2. `setInteractive(true)` (или `initialize()`) запускает `SyncScheduler.start()`, который стартует корутину `tick()`.
3. При `changeToken == null` метод `tick()` до захвата `syncMutex` выполняет сетевой запрос `feed.getLatestChangeToken()` (~200 мс).
4. Пока `tick()` ждёт ответа от Google Drive, вызов `rt.syncOnce()` (`SyncScheduler.runNow()`) захватывает свободный `syncMutex`, сбрасывает `rerunRequested.set(false)` и начинает **прогон №1** (`localVault.list()` + `remoteStorage.listAll()`).
5. Через 200 мс `getLatestChangeToken()` в `tick()` завершается и вызывает `requestSync()`, выставляя `rerunRequested.set(true)`.
6. По завершении **прогона №1** строка `if (rerunRequested.get() && syncMutex.tryLock()) launchRunner()` в `runNow()` немедленно запускает **прогон №2** с повторным полным сканированием всех файлов.

### 1.3. Сопутствующие дефекты, блокирующие фоновую работу и восстановление

1. **Потеря офлайн-фоллбэка в `Canary.kt`:** `verifyOrInitCanary` ловит только `IOException` для проверки локального `.canary_hash`, тогда как `GoogleDriveStorage` при отсутствии сети бросает `RemoteUnavailableException` (наследник `Exception`, а не `IOException`). При старте без сети `verifyOrInitCanary` аварийно завершается вместо использования `.canary_hash`.
2. **Несохранённый `PREF_GIS_ACCOUNT` для фонового `WorkManager`:** при авторизации через Google Identity Services (`Scope(drive.file)` без `email`-скоупа) `AuthorizationResult.toGoogleSignInAccount()?.email` возвращает `null`, поэтому `ActivitySystemAccountLogin` не записывает email в `SharedPreferences` (`gis_account`). Email узнаётся позже через Drive API (`about.get`) в `SystemAccountLoginProvider`, но сохраняется только в `files/account.json`. В фоновом процессе `MimirSyncWorker` вызов `PlayServicesGisAuthorizer.authorizeSilently()` без `setAccount(Account(email, "com.google"))` не может выбрать аккаунт без UI.
3. **Отсутствие регистрации `WorkManager` после первичной настройки:** `MimirApplication.onCreate()` вызывает `workScheduler.scheduleVaultTrigger()` и `workScheduler.schedulePeriodic()` только если `runtime.vaultPath != null` в момент старта процесса. После прохождения мастера настройки (`configureVault`) фоновые воркеры не ставятся в расписание до перезапуска приложения.
4. **Старт `SyncScheduler` внутри фонового процесса `WorkManager` (`PID 11274`):** `MimirRuntime` инициализировал `interactive = true` по умолчанию. Когда Android запускал процесс в фоне исключительно для `MimirSyncWorker` (без открытия `MainActivity`, где `ProcessLifecycleOwner` никогда не вызывает `onStart`/`onStop`), вызов `rt.initialize()` внутри воркера запускал бесконечный 60-секундный таймер `SyncScheduler` и `UpdateScheduler`. Через минуту после завершения задачи `WorkManager` этот таймер просыпался в фоне под блокировкой `APP_BACKGROUND` и публиковал ложное уведомление «Нет подключения к интернету» при выключенном экране.
5. **Блокировка кнопки «Синхронизировать сейчас» в `StatusScreen.kt`:** кнопка активна только при `status == SyncStatus.Idle`. В состояниях `SyncStatus.Offline` и `SyncStatus.Error` пользователь лишён возможности вручную повторить синхронизацию.

---

## 2. Цели, не-цели и анти-цели

- **Цели (Goals):**
  1. Исключить краш процесса (`FATAL EXCEPTION`) и ложный переход в `SyncStatus.Offline` при сворачивании приложения или выключении экрана во время синхронизации.
  2. Гарантировать передачу прерванной при уходе в фон синхронизации (а также несинхронизированных локальных правок) в `WorkManager` (`SyncWorkScheduler.scheduleExpedited()`).
  3. Устранить двойной прогон синхронизации при старте и возврате приложения на передний план (`onStart`), а также при завершении мастера настройки.
  4. Обеспечить безотказную фоновую авторизацию `PlayServicesGisAuthorizer` в `MimirSyncWorker` / `VaultChangeWorker` и работу офлайн-проверки `.canary_hash` при `RemoteUnavailableException`.
  5. Разблокировать ручной запуск синхронизации из состояний `SyncStatus.Offline` и `SyncStatus.Error` и автоматический выход из `Offline` при появлении сети.
- **Не-цели (Non-Goals):**
  - Введение постоянной `ForegroundService` с неотключаемым уведомлением.
  - Изменение формата `index.json`, алгоритма `SyncPlanBuilder` или криптографии `RcloneCipher`.
- **Анти-цели (Anti-Goals):**
  - Выполнение долгих сетевых операций в `applicationScope` после `ProcessLifecycleOwner.onStop` в обход `WorkManager`.
  - Подавление ошибок пустыми `catch (_: Throwable)` без сохранения инварианта `CancellationException`.

---

## 3. Инварианты Mimir

1. **Платформы:**
   - **Android (`:apps:android`):** при `onStop` интерактивный раннер в `MimirRuntime` кооперативно отменяется (`cancelRunner = true`, `DefaultSyncEngine` переходит в `SyncStatus.Idle` и атомарно сохраняет уже применённые записи в `index.json` под `NonCancellable`), а незавершённая работа передаётся в `WorkManager` (`scheduleExpedited`), который обладает легитимным сетевым бюджетом `SystemJobService`. Периодические и контентные триггеры `WorkManager` регистрируются реактивно при появлении `vaultPath`.
   - **Linux / Windows / macOS / Docker (`:apps:daemon`, `:apps:desktop`):** работают в режиме `interactive = true` постоянно; устранение гонки `changeToken` в `SyncScheduler` и обработка `RemoteUnavailableException` в `verifyOrInitCanary` одинаково защищают десктоп и демон от двойного стартового сканирования и падения при офлайн-старте.
   - **JS-клиенты (`:apps:web-ui`, `:apps:obsidian-plugin`, `:apps:browser-extension`):** `StatusScreen` в `:libs:ui` (общий для desktop, Android и `wasmJs`) позволяет вручную запустить повторную синхронизацию при статусах `Offline` и `Error`.
2. **Дизайн:**
   - Сохраняется архитектура **Порты и адаптеры** и **Единый бэкенд (`MimirRuntime`)**: управление интерактивным режимом и координация планировщика инкапсулированы в `MimirRuntime` (`setInteractive`) и `SyncScheduler`, а платформенная передача в `WorkManager` и наблюдение за `AndroidNetworkState` остаются в composition root `:apps:android` (`MimirApplication`).
3. **IO:**
   - При открытии приложения на Android или десктопе вместо двух подряд полных обходов (`2 × localVault.list()` + `2 × remoteStorage.listAll()`) выполняется ровно один прогон при холодном старте (`changeToken == null`) либо один легковесный запрос `changes.list(pageToken)` при возврате из фона (`changeToken != null`), если локальных изменений и ошибок не было.
   - При выключении экрана исключается 5-секундное «подвисание» открытого сокета до сетевого обрыва `APP_BACKGROUND` и последующий холостой повтор уже скачанных файлов: `index.flush()` в `DefaultSyncEngine` сохраняет прогресс до точки отмены.
4. **UX:**
   - При работающем Wi-Fi / мобильном интернете и выключении экрана в шторке уведомлений не появляется ложное сообщение «Нет подключения к интернету».
   - На экране статуса (`StatusScreen`) кнопка «Синхронизировать сейчас» доступна в состояниях `Idle`, `Offline` и `Error(...)` (заблокирована только во время `Syncing` и `NotConfigured`).
   - При восстановлении сети на телефоне в интерактивном режиме статус `Offline` автоматически сменяется новой проверкой синхронизации без необходимости перезапускать приложение.

---

## 4. Рассмотренные подходы (Approaches)

### 4.1. Поведение при сворачивании приложения (`onStop`) во время активной синхронизации

| Подход | Суть | Компромиссы | Решение |
| :--- | :--- | :--- | :--- |
| **A. Кооперативная отмена foreground-раннера (`cancelRunner = true`) + эстафета в `WorkManager` (`scheduleExpedited`)** | В `setInteractive(false)` отменяется `scheduler.runner` (но не `runNow()`, если уже работает `MimirSyncWorker`). Если синхронизация шла или есть `pendingLocalChanges`, `MimirAppLifecycleObserver.onStop` вызывает `workScheduler.scheduleExpedited()`. | `DefaultSyncEngine` при `CancellationException` штатно сбрасывает накопленный `index` на диск и ставит `SyncStatus.Idle`. Оставшиеся файлы докачиваются в `MimirSyncWorker` с системным сетевым разрешением. | **Выбран** |
| **B. Оставлять `cancelRunner = false` и глушить `RemoteUnavailableException` в фоне** | Не отменять `runner`, но игнорировать сетевые ошибки после `onStop`. | Android всё равно принудительно рвёт сокеты через 5 с (`blocked=APP_BACKGROUND`), тратится батарея и трафик на оборванные запросы, а `WorkManager` при этом не запускается. | Отклонён |
| **C. Повышать каждый прогон до `ForegroundService`** | Запускать `Service` с постоянным уведомлением при любом `onStop`. | Прямо противоречит не-цели спеки `2026-10-27-mimir-android-design.md` (§2: «без постоянной foreground-службы») и ограничениям Android 14+ на `dataSync` foreground service. | Отклонён |

### 4.2. Устранение двойного прогона при старте и `onStart`

| Подход | Суть | Компромиссы | Решение |
| :--- | :--- | :--- | :--- |
| **A. Единая точка запуска через `scheduler.start()` + защита первичной инициализации `changeToken` в `SyncScheduler`** | Убрать лишний `rt.syncOnce()` из `MimirAppLifecycleObserver.onStart`. В `SyncScheduler.tick()` учитывать `_pendingLocalChanges.value` и при первичной инициализации `changeToken == null` вызывать `triggerSync()` только если за время `getLatestChangeToken()` не начался другой прогон (`syncStartCount`). | Убирает и дублирующий вызов в Android lifecycle, и фундаментальную гонку в `SyncScheduler`, если `triggerSync()`/`runNow()` вызван параллельно со стартом планировщика (например, из мастера настройки). | **Выбран** |
| **B. Оставить `rt.syncOnce()` в `onStart`, но не запускать `tick()` сразу в `scheduler.start()`** | Отложить первый `tick()` на `syncInterval` (60 с). | Ломает десктоп и демон (где `scheduler.start()` является единственным триггером первой синхронизации) и оставляет `changeToken == null` на первую минуту работы. | Отклонён |

---

## 5. Детальное техническое решение

### 5.1. `SyncScheduler` (`libs/runtime/.../SyncScheduler.kt`)

1. **Счётчик запусков и признак активного `runner`:**
   - Добавить `private val syncStartCount = AtomicLong(0)`: инкрементируется в начале `runOnce()`.
   - Добавить свойство `val isRunning: Boolean get() = syncMutex.isLocked` (или проверку активности `runner`).
   - В `stop(cancelRunner: Boolean = true)`: если `cancelRunner && runner?.isActive == true`, перед отменой `runner?.cancel()` выставлять `retryOnNextTick.set(true)`, чтобы прерванный прогон гарантированно возобновился при следующем старте планировщика.
2. **Устранение гонки в `tick()`:**
   - В начале `tick()` проверять `if (feed == null || retryOnNextTick.get() || watcherError != null || _pendingLocalChanges.value)`: если есть несинхронизированные локальные изменения или предыдущий прогон был прерван/завершился с ошибкой, сразу вызывать `requestSync()`.
   - В ветке `if (token == null)`:
     ```kotlin
     val countBefore = syncStartCount.get()
     changeToken = feed.getLatestChangeToken()
     if (syncStartCount.get() == countBefore) {
         triggerSync()
     }
     ```
     Если параллельно с `getLatestChangeToken()` уже стартовал или завершился полный прогон (`runNow()` или `triggerSync()`), повторный холостой прогон не запрашивается (`rerunRequested` не взводится).

### 5.2. `MimirRuntime` и `Canary` (`libs/runtime/.../MimirRuntime.kt`, `Canary.kt`)

1. **Офлайн-фоллбэк в `verifyOrInitCanary` (`Canary.kt`):**
   - Вынести проверку сохранённого `.canary_hash` в локальную функцию `verifyOfflineCanaryHash(hashFile: Path, expectedHash: String)` и вызывать её при перехвате как `IOException`, так и `RemoteUnavailableException`.
   - В `MimirRuntime.rebuildLenient()` добавить перехват `RemoteUnavailableException` (с логированием и установкой `_status.value = SyncStatus.Offline`), чтобы сетевой сбой при инициализации не пробрасывался наружу необработанным исключением.
2. **Безопасное переключение `setInteractive(active: Boolean)` и `initialInteractive` (`MimirRuntime.kt`):**
   - Добавить параметр конструктора `initialInteractive: Boolean = true` (для десктопа и демона `true`, для `AndroidRuntimeFactory` — `false`, чтобы старт процесса из `WorkManager` без `MainActivity` не запускал фоновые таймеры).
   - Добавить `val vaultPathFlow: StateFlow<String?>`, обновляемый в `configureVault`, для реактивной регистрации фоновых задач `WorkManager`.
   - Изменить сигнатуру `suspend fun setInteractive(active: Boolean): Boolean`: возвращает `true`, если при переходе в фон (`active = false`) был прерван активный прогон синхронизации или имеются `pendingLocalChanges`.
   - В ветке `active = false`:
     ```kotlin
     val wasSyncing = scheduler?.isRunning == true || _status.value == SyncStatus.Syncing
     updateScheduler?.stop()
     scheduler?.stop(cancelRunner = true)
     assembled?.watcher?.stop()
     conflictTracker.stop()
     return@withLock wasSyncing || _pendingLocalChanges.value
     ```

### 5.3. Жизненный цикл, сеть и фоновая авторизация на Android (`:apps:android`)

1. **`MimirAppLifecycleObserver` (`MimirApplication.kt`):**
   - В `onStart`:
     ```kotlin
     scope.launch {
         try {
             val rt = runtime()
             if (!rt.isReady) {
                 rt.initialize()
             }
             rt.setInteractive(true)
         } catch (e: CancellationException) {
             throw e
         } catch (e: Exception) {
             // Ошибки инициализации уже отражены в rt.status; не роняем процесс.
         }
     }
     ```
     Вызов `rt.syncOnce()` из `onStart` удаляется: `rt.initialize()` (при первом старте) и `rt.setInteractive(true)` (при возврате из фона) сами запускают `scheduler.start()`, который выполняет ровно один стартовый прогон или инкрементальную проверку `changes.list`.
   - В `onStop`:
     ```kotlin
     scope.launch {
         val rt = runtime()
         val needsBackgroundSync = rt.setInteractive(false)
         if (needsBackgroundSync && rt.vaultPath != null) {
             workScheduler().scheduleExpedited()
         }
     }
     ```
2. **Реактивное планирование `WorkManager` и реакция на восстановление сети (`MimirApplication.kt`):**
   - Отслеживать появление `runtime.vaultPath` при изменении `runtime.status` (переход из `NotConfigured` в рабочее состояние) и идемпотентно вызывать `workScheduler.scheduleVaultTrigger()` и `workScheduler.schedulePeriodic()` (с `ExistingPeriodicWorkPolicy.KEEP` / `ExistingWorkPolicy.KEEP`).
   - Подключить `AndroidNetworkState(this)` в `MimirApplication`: когда приложение находится в интерактивном режиме (`ProcessLifecycleOwner` в `STARTED`/`RESUMED`), а сеть переходит в `ONLINE` / `METERED` при `runtime.status.value == SyncStatus.Offline`, вызывать `runtime.triggerSync()`.
3. **Сохранение аккаунта GIS для фоновых воркеров (`AndroidRuntimeFactory.kt`, `PlayServicesGisAuthorizer.kt`, `GisTokenSource.kt`):**
   - В `AndroidRuntimeFactory`: при успешном получении `email` через `emailFetcherClient.fetchAccount(token)` записывать его в `accountPrefs` (`PREF_GIS_ACCOUNT`). При создании `PlayServicesGisAuthorizer` передавать резервный поставщик email (`fallbackAccountEmail = { AccountStore(dataDir).email() }`), чтобы уже авторизованные пользователи v0.4.0 сразу получили рабочий `Account(email, "com.google")` в фоновых воркерах без повторного входа.
   - В `PlayServicesGisAuthorizer.authorizeSilently()`: сетевые коды `ApiException` (`CommonStatusCodes.NETWORK_ERROR`, `TIMEOUT`, `INTERNAL_ERROR`, `API_NOT_CONNECTED`) преобразовывать в `java.io.IOException`, чтобы `DriveHttpClient` и `Canary` штатно трактовали их как `RemoteUnavailableException` (`Offline`), а не как фатальный сбой.
   - В `GisTokenSource.hasCredentials()`: если `authorizeSilently()` выбрасывает `IOException` (устройство офлайн в момент холодного старта), но аккаунт уже сохранён в `authorizer.hasSavedAccount()`, возвращать `true`, позволяя `EngineAssembler` собрать движок в офлайне по `.canary_hash`.

### 5.4. Разблокировка кнопки синхронизации в `StatusScreen` (`libs/ui/.../StatusScreen.kt`)

- Изменить условие активности кнопки «Синхронизировать сейчас» (`status_sync_now_button` в `StatusScreen.kt:176`):
  ```kotlin
  enabled = status == SyncStatus.Idle || status == SyncStatus.Offline || status is SyncStatus.Error
  ```

---

## 6. План верификации

1. **Модульные тесты (`:libs:runtime:jvmTest`, `:apps:android:testDebugUnitTest`, `:libs:ui:desktopTest`):**
   - Тест `SyncSchedulerTest`: параллельный вызов `start()` (с задержкой `getLatestChangeToken()`) и `runNow()` / `triggerSync()` выполняет `syncNow()` ровно **один** раз, а не два.
   - Тест `SyncSchedulerTest`: вызов `stop(cancelRunner = true)` во время активного прогона прерывает его и взводит `retryOnNextTick`, так что следующий `start()` перезапускает синхронизацию.
   - Тест `CanaryTest` / `MimirRuntimeTest`: при выбросе `RemoteUnavailableException` из `remote.stat` и наличии валидного `.canary_hash` `verifyOrInitCanary` успешно завершается, а `MimirRuntime.initialize()` собирает движок без падения.
   - Тест `MimirAppLifecycleObserverTest`:
     - `onStart` не вызывает `syncOnce()` поверх `initialize()` / `setInteractive(true)` и не роняет скоуп при сетевом сбое;
     - `onStop` во время активной синхронизации (`SyncStatus.Syncing`) отменяет foreground-прогон и вызывает `workScheduler.scheduleExpedited()`.
   - Тест `PlayServicesGisAuthorizerTest` / `GisTokenSourceTest`: использование `fallbackAccountEmail` при пустом `PREF_GIS_ACCOUNT` и возврат `hasCredentials() == true` при `IOException` в офлайне для сохранённого аккаунта.
   - Тест `StatusScreenTest`: кнопка `status_sync_now_button` активна (`assertIsEnabled()`) при `SyncStatus.Offline` и `SyncStatus.Error`.
2. **Проверка на подключённом телефоне (`R5CY21Q782V`):**
   - Сборка и установка debug-APK поверх / рядом и проверка через `adb logcat`:
     - При открытии приложения выполняется ровно 1 цикл синхронизации.
     - При выключении экрана во время синхронизации нет `FATAL EXCEPTION`, нет ложного уведомления «Нет подключения к интернету», а задача передаётся в `MimirSyncWorker`.
3. **Аудит базы знаний:** `context_check` / `rtk context-init check --compact`.
