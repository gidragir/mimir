---
name: mimir-cross-platform
description: Use when a Mimir change touches the OS, window, tray, focus, file chooser, browser, notifications, processes, file paths, config dirs, permissions or packaging, or when a fix was checked only on the Arch/niri dev machine — "works on my machine", xdg-open, niri, os.name, ProcessBuilder, Wayland, Windows, Android.
---

# Кросс-платформенность Mimir

## Суть

Arch + niri — машина разработчика, а не продукт. Функция готова, когда для **каждой** цели известно, что она там делает: работает, деградирует к понятному запасному пути или скрыта в интерфейсе.

## Цели

| Цель | Среда | О чём помнить |
|---|---|---|
| Linux desktop | Arch, системная Java ≥ 27; niri/Sway, KDE, GNOME; Wayland и X11 | AWT-трей не работает без SNI (`DbusStatusNotifierTray`); диалог выбора папки — XDG Desktop Portal (Nautilus/Dolphin), fallback на `zenity`/`kdialog` перед AWT; плавающие трей-поповеры обязаны выставлять `Window.Type.POPUP`, чтобы niri/Sway не тайлили их в колонки; главное окно не использует `undecorated = true` и не рисует дублирующую внутреннюю шапку в тайлинговых WM; для панелей DE сохраняется параллельный экспорт `DbusMenu`; портал XDG есть не везде; `xdg-open` может отсутствовать; эмулятор Android SDK под Wayland требует `QT_QPA_PLATFORM=xcb` (встроенный Qt не имеет плагина wayland) |
| Windows desktop | MSI per-user, вшитая JRE, без админа | нативный проводник Windows (Common Item Dialog / `FolderBrowserDialog` с `AutoUpgradeEnabled=true` через скрытый PowerShell-раннер без мелькания консоли); AWT-трей и `Desktop.browse`; `%APPDATA%`; пути `C:\…`; ФС без учёта регистра (`Note.md` = `note.md`); `<>:"\|?*`, `CON`/`NUL`, MAX_PATH; файл, открытый Obsidian, может быть заблокирован; `mimir://` URI scheme регистрируется при старте приложения в `HKCU\Software\Classes\mimir` через `reg import` (не надеяться на `appResourcesRootDir` в WiX/jpackage); для Win32 FFM обязателен `--enable-native-access=ALL-UNNAMED` в `jvmArgs`; вывод окна на передний план требует сброса `window.isAlwaysOnTop = true; window.isAlwaysOnTop = false` перед `toFront()` |
| macOS desktop | `.app` / DMG, системный Cocoa | нативный диалог Finder (`NSOpenPanel`) через `java.awt.FileDialog` с флагом `apple.awt.fileDialogForDirectories = "true"`; AWT-трей (`SystemTray`); путь к данным `~/Library/Application Support/mimir`; кейсонечувствительная APFS |
| Android (Android 16 / API 36) | APK, `MANAGE_EXTERNAL_STORAGE`, `WorkManager` | нативный системный менеджер файлов `DocumentsUI` через Activity Result SAF (`ActivityResultContracts.OpenDocumentTree`); нет AWT/Swing и внешних команд; Activity пересоздаётся; платформенные сервисы с Activity Result (SAF `OpenDocumentTree`, GIS `IntentSender`) требуют composable-обёртку на базе `rememberLauncherForActivityResult` + `CompletableDeferred`; при получении `Activity.RESULT_CANCELED` от Play Services GIS обязательно проверять `intent.data` через `Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)` и транслировать `ApiException` в `SystemLoginResult.Unavailable`, не маскируя ошибку под отмену пользователем; при скоупе `drive.file` `toGoogleSignInAccount()?.email` равен `null` — email из Drive `about.get` обязан кэшироваться в `PREF_GIS_ACCOUNT` с fallback на `account.json` для фонового `authorizeSilently()`; через 5 с после `onStop` Android включает `blocked=APP_BACKGROUND` и рвёт сокеты процесса, поэтому `MimirRuntime` на Android создаётся с `initialInteractive = false`, при `onStop` отменяет интерактивный раннер (`cancelRunner = true`) и передаёт незавершённую синхронизацию в `WorkManager` (`scheduleExpedited()`); Java NIO `WatchService` не поддерживается (только `android.os.FileObserver`); `FOREGROUND_SERVICE_DATA_SYNC` ограничен 6 часами (синхронизация через `WorkManager`); обязательный edge-to-edge (`safeDrawingPadding()`); сенсорные мишени ≥ 48dp; межпроцессный loopback HTTP (`127.0.0.1`) к Obsidian заблокирован политиками WebView/Doze |
| Docker-демон | Linux glibc, без дисплея | нет окна, браузера, трея; настройка только env-переменными; вход — Device Code; UI — web-ui по HTTP API |
| JS-клиенты | плагин Obsidian (desktop RPC), Chromium MV3, web-ui (wasm) | нет `java.*` и прямой ФС; плагин ходит в локальный HTTP API демона (на сервере обязателен CORS); расширение Chromium (`:apps:browser-extension`) переиспользует KMP-модули `:libs:drive` (`GoogleDriveStorage`, `GoogleAuthClient`) и `:libs:core` (`EncryptedRemoteStorage`, `RcloneCipher`), открывается в `chrome.sidePanel` (Master-Detail) и полноэкранной вкладке (`?mode=tab`) с fallback в Popup; в `chrome.storage.session` (RAM) хранится 80-байтный ключ `RcloneCipher`, а в `chrome.storage.local` — только зашифрованные `RemoteEntry` и `.canary_hash` |

## Рецепт

1. **До кода** заполнить в спеке/плане таблицу «Платформы»: цель → реализация / запасной путь / «скрыто, потому что…». Пустая строка = задача не продумана.
2. **Общее — интерфейс, частное — реализация.** Контракт живёт в общем слое: в `libs/ui` — CompositionLocal (`LocalFolderPicker`, `LocalUriHandler`, `LocalBringToFront`), в `libs/core` — порт или минимальный `expect/actual`. Реализации — в модуле точки входа (`apps/desktop/…/picker`, `…/tray`).
3. **Выбор реализации — одна фабрика** (`defaultFolderPicker()`, `createTray()`): 
   - **Диалоги выбора папки/файла ОБЯЗАНЫ вызывать стандартный системный проводник ОС:**
     - Windows → Нативный Проводник Windows (`WindowsFolderPicker`: `FolderBrowserDialog` с `AutoUpgradeEnabled = true` поверх окна).
     - macOS → Нативный Finder (`MacFolderPicker`: `FileDialog` с `apple.awt.fileDialogForDirectories = true`).
     - Linux → Нативный файловый менеджер среды (Nautilus/Dolphin) через XDG Portal (`PortalFolderPicker`), fallback на `zenity`/`kdialog`.
     - Android → Нативный системный менеджер файлов `DocumentsUI` через SAF (`AndroidFolderPicker`).
   - Swing `JFileChooser` допускается **исключительно** как аварийный headless/unsupported fallback и **обязан** предварительно устанавливать `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())` для исключения устаревшего стиля Metal.
   - No-op допустим только там, где нет ни специфичного, ни общего API; на desktop общий путь почти всегда есть (`SystemTray`/`TrayIcon.displayMessage`, `Desktop`, Swing). Цель, отложенная на потом, — отдельная задача в плане, а не «в долге». Проверки `os.name`/env — только в фабриках и конструкторах с параметрами по умолчанию (как `SystemBrowserOpener(osName, launch, browse)`), никогда в `libs/core`/`libs/ui`.
4. **Хост-специфичное — надстройка, не единственный путь.** Антипример: `CompositorFocus` работает только под niri, на Windows/GNOME молчит — пришлось перепроектировать (спека `uri-scheme-login`).
5. **Пути**: относительные пути vault — всегда `/` (`RemotePath`); пути ОС — только `Path`, без склейки строк; каталоги данных — только через `DaemonConfig` (`defaultConfigDir`). Новые имена файлов проверять на правила Windows.
6. **Внешние команды** (`ProcessBuilder`) — только в `apps/desktop`, с тайм-аутом, перехватом `IOException` и запасным путём.

## Проверка

- Хост не доказывает кросс-платформенность. Собрать все цели модуля (`allTests`, `compileKotlinWasmJs`, Android при SDK) — см. `mimir-build-verify`.
- Windows/macOS проверяет CI-матрица (`ci.yml`): логика ветвления по ОС покрывается юнит-тестом с подставленным `osName`/`env`.
- Windows локально: окно, трей, фокус, выбор папки, `mimir://`, MSI — до CI прогнать `mise run win:run` / `win:msi` в VM `win11` (см. `mimir-build-verify`, «Проверка на Windows-VM»). Результат записать в отчёт: что увидено в VM, что только в CI.
- В отчёте явно перечислить, что на каких платформах не проверено руками.

## Красные флаги

- «Под niri работает» без строк про Windows/Android/Docker.
- Правка Windows-ветки, не прогнанная в VM `win11`, но заявленная как проверенная.
- `os.name`, `System.getenv`, `ProcessBuilder`, `java.awt` в `libs/core` или `libs/ui/commonMain`.
- Linux-only библиотека в `commonMain` или без запасного пути.
- Захардкоженные `/home`, `~/.config`, `\n` в путях, `File.separator` в относительных путях vault.
- Функция, которой нет на платформе, но кнопка для неё видна.
- «На Windows пока no-op», когда есть AWT/JDK API.
- Вызов Swing `JFileChooser` или стандартного Swing-диалога на Windows или macOS вместо нативного Проводника / Finder.
- Использование `JFileChooser` без установки системного Look & Feel (`UIManager.getSystemLookAndFeelClassName()`).
- Java NIO `WatchService` в Android-коде.
- Инициализация платформенных Android-сервисов с Activity Result через `remember { Service(this) }` без лаунчера.
- Трактовка любого `RESULT_CANCELED` в Google Play Services как отмены пользователем без проверки `ApiException` в `intent.data`.
- Оставление активного сетевого прогона в `applicationScope` после `onStop` на Android (`cancelRunner = false`) или создание `MimirRuntime` с `initialInteractive = true` в Android-процессе (приводит к обрыву сокетов `APP_BACKGROUND`, ложному `SyncStatus.Offline` и крашу).
- Вызов `rt.syncOnce()` (`runNow()`) параллельно с `scheduler.start()` в `onStart` (вызывает двойной полный обход хранилища и необработанный краш при ошибке сети).
- Сенсорные мишени < 48 dp на мобильных экранах; отсутствие `safeDrawingPadding()`.
- Встраивание Content Script в DOM `drive.google.com`, дублирование Google Drive API поверх `fetch` в обход `:libs:drive` или сохранение расшифрованных имён/текстов заметок в `chrome.storage.local` в `:apps:browser-extension`.
- Всплывающие десктопные поповеры/виджеты без флага `Window.Type.POPUP`, ломающие тайлинг в Wayland niri/Sway.
