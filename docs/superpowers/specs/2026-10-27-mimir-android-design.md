# Mimir: Android-приложение (MVP-синхронизатор)

**Дата:** 2026-10-27
**Статус:** Draft (дизайн согласован по разделам, ждёт ревью спеки)
**Область:** новый модуль `runtime`; `daemon`, `drive`, `ui`, `app-desktop`, `app-android`, CI

---

## 1. Контекст

Главная боль из основной спеки (`2026-10-23-mimir-design.md`, §1): на Android правка с телефона «не уходит», отсюда непонятные конфликты. Сейчас `app-android` — заготовка: `MainActivity` запускает `MimirApp(StubSyncEngine())`, `MimirSyncWorker` ни к чему не подключён и держит движок в статическом поле `companion`.

Готово к использованию: в `core/androidMain` — `AndroidFileObserverVaultWatcher`, `AndroidKeystoreSecretStore`; `ui` и `client` собираются под Android; edge-to-edge и сенсорные мишени ≥ 48dp сделаны (спека `2026-10-26-mimir-improvements-design.md`).

Блокер: вся сборка бэкенда (вход, разблокировка, пересборка движка, планировщик, конфликты, индекс) живёт в `daemon/DaemonRuntime` — модуле `kotlin("jvm")` под JDK 27 с зашитыми `NioWatchServiceVaultWatcher`, `FileTokenStorage`, каталогами ОС и входом Browser/Device Code. Android не может зависеть от этого модуля (байткод JDK 27, `WatchService` на Android не работает).

## 2. Цели и не-цели

### Цели
1. Мастер настройки на телефоне: доступ к файлам → папка vault → вход в Google → пароль → первое слияние.
2. Фоновая двусторонняя синхронизация: правка в Obsidian уходит в Drive **примерно за минуту, даже если Mimir закрыт**; без постоянной foreground-службы.
3. Статус всегда виден: уведомление и экран статуса; экран конфликтов.
4. Вход в Google через **системное окно выбора аккаунта Android** (Google Identity Services); при отсутствии Play Services — вход в браузере.
5. Единый бэкенд для daemon, desktop и Android (без копий логики синхронизации).

### Не-цели (отдельные спеки)
- HTTP API на телефоне для плагина Obsidian (§6.2 основной спеки; loopback из Obsidian mobile под вопросом — см. скилл `mimir-cross-platform`).
- Виджет на рабочий стол, плитка быстрых настроек.
- Google Play, SAF вместо `MANAGE_EXTERNAL_STORAGE`.
- Foreground-служба синхронизации.

### Анти-цели
- **Device Code на Android** — несколько ручных действий, плохой UX. На Android не используется ни как основной, ни как запасной путь.
- Логика синхронизации, планирования или входа в `app-android` (только сборка графа и Android-адаптеры).

## 3. Рассмотренные подходы

| Подход | Суть | Решение |
|---|---|---|
| **A. Общий модуль `runtime`** | Из `daemon` выносится KMP-модуль (jvm + android) с фасадом `MimirRuntime : DaemonService`; платформенное — за портами; `daemon`, `app-desktop`, `app-android` — composition root'ы | **Выбран** |
| B. `daemon` → KMP | Один модуль с целями jvm + android | Отклонён: Android-сборка тянет Ktor-сервер, env-конфиг и BuildConfig точки входа; `DaemonRuntime` продолжает расти |
| C. Свой бэкенд в `app-android` | Сборка поверх `core`/`drive` | Отклонён: копия планировщика, входа, конфликтов; нарушает «единый бэкенд» |

A соответствует каталогу `mimir-design-patterns`: порт и адаптер, composition root, реестр способов входа, стратегия получения токена, деление класса > ~300 строк до добавления нового.

## 4. Архитектура

### 4.1 Модули

```
core        без изменений (порты VaultWatcher, SecretStore, NetworkState, SyncEngine; androidMain-адаптеры)
drive       + порт AccessTokenSource и RefreshingTokenSource (текущая логика refresh из DriveHttpClient)
runtime     НОВЫЙ KMP (jvm + android через mimir.android-support):
              MimirRuntime : DaemonService (фасад, состояние), EngineAssembler (сборка движка),
              SyncScheduler, JsonSyncStateStore, ConflictFiles, Canary, AtomicFiles,
              RuntimeConfig, LoginProvider + LoginRegistry,
              BrowserLoginProvider + BrowserLoginReceiver (Ktor CIO работает и на Android)
client      без изменений
ui          + CompositionLocal LocalSystemAccountLogin; шаг «Доступ»; Android-баннеры
daemon      JVM-точка входа: Main, DaemonConfig (env/файл) → RuntimeConfig, Routes/Ktor-сервер,
              DeviceCodeLoginProvider, EmbeddedGoogleClients, DaemonBuildConfig
app-desktop как было; импорты из runtime
app-android composition root: MimirApplication, MainActivity, Worker'ы, Android-адаптеры портов
```

Зависимости только вниз: точки входа → `ui` → `runtime` → `drive` → `core`. `java.nio.file` в `runtime` допустим: доступен на Android с API 26 (= minSdk).

### 4.2 Порты и реализации

| Порт | Модуль порта | JVM (daemon, desktop) | Android |
|---|---|---|---|
| `VaultWatcherFactory` | `runtime` | `NioWatchServiceVaultWatcher` | `AndroidFileObserverVaultWatcher` |
| `AccessTokenSource` | `drive` | `RefreshingTokenSource` (refresh-токен) | `GisTokenSource`; при входе через браузер — `RefreshingTokenSource` |
| `LoginProvider` (+ `LoginRegistry`) | `runtime` | `BrowserLoginProvider`, `DeviceCodeLoginProvider` | `SystemAccountLoginProvider` (GIS), `BrowserLoginProvider` |
| `TokenStorage` | `drive` | `FileTokenStorage` | `SecretStoreTokenStorage` поверх `AndroidKeystoreSecretStore` |
| `SecretStore` (пароль) | `core` | файл / env / Docker secret | `AndroidKeystoreSecretStore` |
| `NetworkState` | `core` | как сейчас | адаптер на `ConnectivityManager` |
| `RuntimeConfig.dataDir` | `runtime` | `defaultConfigDir()` | `context.filesDir` |

### 4.3 `DaemonRuntime` → `MimirRuntime`

- Вход (`startBrowserLogin`, `startDeviceLogin`, `completeLogin`) уходит в провайдеры; пересборка движка (`rebuild`, `teardown`) — в `EngineAssembler`. `MimirRuntime` хранит состояние и делегирует; цель — ≤ ~300 строк.
- Контракт `DaemonService` (`:client`): пары `startBrowserLogin`/`pollBrowserLogin` и `startDeviceLogin`/`pollDeviceLogin` заменяются на `loginMethods()` + `startLogin(method)` + `pollLogin()`. HTTP-маршруты демона и `HttpDaemonClient` сохраняют прежний JSON — плагин и web-ui не ломаются.
- `DriveHttpClient` принимает `AccessTokenSource` вместо прямой работы с `TokenStorage`; повтор при 401 — один, как сейчас.

## 5. Вход в Google

Порядок в `LoginRegistry` на Android: **GIS → браузер**. Браузер предлагается, если Play Services недоступны (`GoogleApiAvailability`) или GIS вернул ошибку конфигурации (например, неизвестный SHA-1).

### 5.1 Основной путь — Google Identity Services
- В том же проекте GCP, что Desktop/Device-клиенты (`drive.file` привязан к проекту), заводятся два Android OAuth-клиента: package `mimir.appId`, SHA-1 debug-ключа и SHA-1 релизного ключа CI. Client ID в код не попадает. README: как добавить SHA-1 своего debug-ключа.
- Шаг Google мастера → `LocalSystemAccountLogin.signIn()` → `Identity.getAuthorizationClient(activity).authorize(scope = drive.file)`; при `hasResolution()` — системное окно выбора аккаунта и согласия через `ActivityResultLauncher<IntentSenderRequest>`.
- Email — из Drive `about.get(fields=user(emailAddress))` (доступен при `drive.file`); результат передаётся в `MimirRuntime.startLogin(SystemAccount)`, дальше общий путь: `account.json`, пересборка движка.
- `GisTokenSource`: токен в памяти ~50 минут (GIS не отдаёт срок жизни), затем молчаливый `authorize()` с запомненным аккаунтом (`setAccount`) — работает из Worker через `applicationContext`. При 401 — `clearToken` и повторный запрос (один раз). Если требуется действие пользователя — `DriveAuthException` → статус «нужен вход» → уведомление «Войдите в Google снова»; синхронизация останавливается, данные не трогаются.

### 5.2 Запасной путь — браузер
- Тот же поток, что на desktop: PKCE, Desktop OAuth-клиент, `BrowserLoginReceiver` на `127.0.0.1:<свободный порт>`.
- Страница входа открывается в Custom Tab (`androidx.browser`), без Custom Tabs — `ACTION_VIEW`. Приёмник отвечает редиректом на `mimir://authorized` (схема из спеки `2026-10-24-mimir-uri-scheme-login-design.md`, без токенов и кодов); intent-filter в манифесте возвращает пользователя в `MainActivity`.
- Refresh-токен — в `SecretStoreTokenStorage` (Keystore), обновление — `RefreshingTokenSource`.
- Риск: loopback-вход Google документирует для desktop-клиентов; на Android работает технически, официальным путём не является. Поэтому он только запасной.

### 5.3 Выход
GIS — `revokeAccess`; браузер — отзыв refresh-токена. Затем удаляется `account.json`; vault и индекс не трогаются.

## 6. Фоновая работа

### 6.1 Composition root
`MimirApplication : Application, Configuration.Provider` лениво собирает один `MimirRuntime` на процесс и отдаёт его Worker'ам через `WorkerFactory`. Статическое `MimirSyncWorker.syncEngineProvider` удаляется. Worker'ы идут в процессе приложения, поэтому прогоны из Worker'а и с экрана сериализуются одним мьютексом `SyncScheduler`.

### 6.2 Режимы `MimirRuntime` (по `ProcessLifecycleOwner`)

| Режим | Когда | Что работает |
|---|---|---|
| Активный | приложение на экране | `SyncScheduler` целиком: `AndroidFileObserverVaultWatcher` с debounce, опрос Change Feed, кнопка «Синхронизировать» |
| Фоновый | свёрнуто / процесс убит | планировщик и наблюдатель остановлены; прогоны запускает только WorkManager через `runtime.syncOnce()` |

### 6.3 Триггеры WorkManager
Уникальные имена работ; ограничение сети `CONNECTED`, при `SyncRules.wifiOnly` — `UNMETERED`.

1. **Изменения в vault** — `OneTimeWorkRequest` с `addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)`, `setTriggerContentUpdateDelay(10s)`, `setTriggerContentMaxDelay(60s)`. Worker перепланирует себя в начале каждого прогона. Фильтр: пути `triggeredContentUris` одним запросом к `ContentResolver`; синхронизация только если путь внутри vault, не исключён `SyncRules` и не записан движком (`EchoFilter`).
2. **Периодическая** — каждые 15 минут: страховка и приём чужих правок.
3. **Срочная** (`setExpedited`) — при уходе приложения в фон, если остались неотправленные изменения.

Задачи переживают перезагрузку средствами WorkManager.

### 6.4 Длинные прогоны
Первая полная синхронизация идёт в мастере, на экране, с прогрессом. Фоновый Worker ограничен ~10 минутами; при прерывании индекс уже сохранён (`IndexUpdater`, каждые 100 изменений), следующий прогон продолжает. Foreground-службы нет (Android 12+ запрещает старт из фона).

### 6.5 Уведомления
- Канал «Статус» (низкая важность, без звука), обновление по итогу прогона: «Всё синхронизировано · 12:40», «3 изменения не отправлены», «Нет сети — изменения отправятся позже».
- Канал «Требует внимания» (обычная важность): «Конфликт: 2 файла», «Войдите в Google снова», «Синхронизация остановлена: Drive пуст» (`SyncAbortedException`).
- Без foreground-службы уведомление на Android 14+ смахивается; смахнутое появляется снова только при ухудшении статуса.
- Нажатие открывает `MainActivity` с extra `destination` (`status` | `conflicts` | `login`).

## 7. UI

### 7.1 Мастер (общие экраны `ui`, отличия — через CompositionLocal и состояние модели)
0. **Доступ** (только пока не выдан): `MANAGE_EXTERNAL_STORAGE` через `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, перепроверка на `ON_RESUME`; затем `POST_NOTIFICATIONS` (отказ не блокирует).
1. **Папка vault**: список найденных хранилищ (папки с `.obsidian` в `Documents/` и корне памяти, глубина 2) + «Выбрать другую папку» — Android-реализация `LocalFolderPicker` через `ACTION_OPEN_DOCUMENT_TREE`, tree-URI → путь через `StorageManager`/`StorageVolume.directory`; непереводимый URI (облачный провайдер SAF) — понятная ошибка.
2. **Google**: «Войти через Google» → системное окно; без Play Services тот же шаг открывает браузер.
3. **Пароль**, 4. **Первое слияние** — существующие `PasswordStep`, `InitialResolutionStep`.

### 7.2 Основные экраны
`StatusScreen`, `ConflictsScreen` плюс на Android: баннеры «Нет доступа к файлам» (кнопка «Выдать») и «Войдите в Google снова»; строка «Фоновая синхронизация: включена · последняя 12:40» / «ждёт Wi‑Fi».

### 7.3 Жизненный цикл и вид
- `MimirUiModel` = `DaemonServiceUiModel(runtime)` внутри `ViewModel`; runtime живёт в `Application` — поворот и пересоздание Activity ничего не теряют.
- «Назад» в мастере — предыдущий шаг (`BackHandler`, predictive back); deep link из уведомления — в `onNewIntent`.
- `MimirTheme` / `ObsidianStyle`, светлая/тёмная по системе; adaptive icon с монохромным слоем вместо `ic_launcher.xml`.
- Тексты — в `SyncTexts`, без терминов «MediaStore», «WorkManager».

## 8. Rulings

- **Вшитый Desktop-клиент в APK.** `MIMIR_BUILD_GOOGLE_DESKTOP_*` попадает в BuildConfig `app-android`. Секрет Desktop-клиента у установленных приложений не секретен (позиция Google); запрет AGENTS.md касается `core`/JS-бандлов, APK — нативная точка входа, как `daemon`. Device-клиент в APK не попадает. AGENTS.md уточняется.
- **`versionCode`** вычисляется из `mimir.version` (`major*10000 + minor*100 + patch`), чтобы APK обновлялся поверх.
- **Отступление от §6.4 основной спеки:** к GIS добавлен запасной вход через браузер (loopback/deep link `mimir://authorized`) для устройств без Play Services.
- **Пропуск Task 1 (device spike) и Task 14 (on-device verification):** проверка на физическом устройстве через adb не может быть выполнена агентами в автономной среде. WorkManager-синхронизация реализована в максимальном объёме (триггер MediaStore + периодическая 15 мин + expedited при уходе в фон); подтверждение задержки на реальном устройстве выносится на ручную верификацию.
- **Работа на ветке feat/android-mvp в основном чекауте:** git в worktree-сессиях блокируется защитным фильтром harness guard. Коммиты формируются точечно только для изменённых файлов задачи.
- **Размещение DeviceCodeLoginProvider:** класс размещён в `runtime/login` (вместо `daemon`), Android-клиент не регистрирует его в `LoginRegistry`.
- **Получение email пользователя:** используется `DriveHttpClient.fetchAccount` вместо прямого вызова `about.get`.
- **Явный ioDispatcher в JsonSyncStateStore:** `ioDispatcher: CoroutineDispatcher` передаётся обязательным параметром из платформенных корней (`daemon`, `app-desktop`, `app-android`) во избежание хардкода `Dispatchers.IO` в `runtime`.
- **Опрос /auth/device/poll:** эндпоинт опрашивает попытку входа, стартованную в текущем процессе демона (`device_code` валидируется на непустоту).
- **Сохранение account.json:** фиксация аккаунта и пересборка графа выполняются при первом получении статуса `Authorized` методом `DaemonRuntime.pollLogin`.
- **Контракт VaultWatcher:** интерфейс `core` расширен методом по умолчанию `start(onWatching: () -> Unit)` для атомарного сканирования после регистрации вотчера в `ConflictTracker`.

## 9. Тестирование и сборка

| Что | Где | Как |
|---|---|---|
| Перенос `DaemonRuntime` → `MimirRuntime` | `runtime/jvmTest` | существующие тесты `daemon` переезжают с кодом и проходят без изменения смысла |
| `LoginRegistry`, провайдеры | `runtime/commonTest` | фейки: выбор GIS → браузер, отмена, ошибки |
| `AccessTokenSource` | `drive/commonTest` | контрактный тест для `RefreshingTokenSource` и фейка: один повтор при 401, `DriveAuthException` при необходимости входа |
| `GisTokenSource`, фильтр `MediaStore`, tree-URI → путь, поиск vault | `app-android/src/test` (Robolectric) | `AuthorizationClient`, `ContentResolver` за тонкими интерфейсами с фейками |
| Worker'ы | `app-android/src/test` | `work-testing`: уникальные имена, ограничения сети, перепланирование content-trigger |
| Экраны (шаг «Доступ», баннеры) | `ui/desktopTest` | Compose UI-тесты с `FakeMimirUiModel`, все состояния |
| Манифест | `ManifestPermissionsTest` | + intent-filter `mimir://authorized`, нет foreground-службы |

- Без Android SDK `mise run build` проходит (Android-цель `runtime` пропускается с предупреждением).
- Версии `play-services-auth`, `androidx.browser`, `lifecycle-process`, `work-testing`, `robolectric` — только в `libs.versions.toml`.
- CI: `ci.yml` (Linux, SDK уже ставится) — `:runtime:allTests`, `:app-android:testDebugUnitTest`; `release.yml` — env `MIMIR_BUILD_GOOGLE_DESKTOP_*` для APK.
- **Первый шаг плана — проверка на устройстве триггера `MediaStore`**: видит ли он правки `.md` из Obsidian и с какой задержкой. Если нет — остаётся периодическая задача + режим «Активный», в спеку пишется `Ruling` с новой целевой задержкой.
- Ручная проверка (эмулятор API 36 или телефон по adb): мастер с GIS и с выключенными Play Services; правка в Obsidian → свернуть → изменение в Drive ≤ ~1 мин; смахивание уведомления; отзыв доступа к файлам.

## 10. Инварианты Mimir

- **Платформы.** Android — всё в этой спеке. Linux/Windows desktop и Docker-демон — поведение не меняется, меняется только размещение кода (`runtime`); проверка — существующие тесты `daemon`/`app-desktop` и CI-матрица. JS-клиенты — неприменимо: контракт HTTP API и `HttpDaemonClient` сохраняют JSON. Без Android SDK сборка проходит.
- **Дизайн.** Порт и адаптер (`VaultWatcherFactory`, `AccessTokenSource`, `LoginProvider`; эталон `RemoteStorage` ↔ `GoogleDriveStorage`), реестр способов входа (эталон `RemoteStorageRegistry`), стратегия токена, composition root (`DaemonServiceFactory` → `MimirApplication`), внедрение в Worker'ы через `WorkerFactory` вместо статического поля, деление `DaemonRuntime` на `MimirRuntime` + `EngineAssembler` + провайдеры.
- **IO.** Прогон без изменений: 1 вызов Change Feed + обход vault по `stat`, без чтения содержимого. Изменения N файлов: N загрузок/скачиваний с `parallelism = 4` на `Dispatchers.IO` (передаётся из `app-android`, как из `daemon`). Триггер `MediaStore`: 1 запрос к `ContentResolver` на пачку URI; посторонние файлы синхронизацию не будят. Поиск vault в мастере — `listFiles` на глубину 2. Фон без постоянной службы; токен GIS кэшируется ~50 минут.
- **UX.** Сценарий: установить APK → «Доступ» → выбрать найденный vault → системное окно аккаунта → пароль → первое слияние → экран статуса; дальше правки уходят в фоне ≤ ~1 мин. Состояния: нет доступа к файлам, отказ в уведомлениях, нет Play Services (браузер), отмена входа, нужен повторный вход, нет сети / ждёт Wi‑Fi, синхронизация идёт / всё синхронизировано / есть неотправленные, конфликт, `SyncAbortedException`. Проверка глазами на эмуляторе API 36 в светлой и тёмной теме.
