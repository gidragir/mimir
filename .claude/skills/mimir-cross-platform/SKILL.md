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
| Linux desktop | Arch, системная Java ≥ 27; niri/Sway, KDE, GNOME; Wayland и X11 | AWT-трей не работает без SNI (`DbusStatusNotifierTray`); портал XDG есть не везде; `xdg-open` может отсутствовать |
| Windows desktop | MSI per-user, вшитая JRE, без админа | AWT-трей и `Desktop.browse`; `%APPDATA%`; пути `C:\…`; ФС без учёта регистра (`Note.md` = `note.md`); `<>:"\|?*`, `CON`/`NUL`, MAX_PATH; файл, открытый Obsidian, может быть заблокирован |
| Android | APK, `MANAGE_EXTERNAL_STORAGE` | нет AWT/Swing и внешних команд; Activity пересоздаётся; фон ограничен; касание, а не мышь; экран ~360dp; правила Wi-Fi |
| Docker-демон | Linux glibc, без дисплея | нет окна, браузера, трея; настройка только env-переменными; вход — Device Code; UI — web-ui по HTTP API |
| JS-клиенты | плагин Obsidian (в т.ч. мобильный), Chromium MV3, web-ui (wasm) | нет `java.*` и прямой ФС; только HTTP API демона |

## Рецепт

1. **До кода** заполнить в спеке/плане таблицу «Платформы»: цель → реализация / запасной путь / «скрыто, потому что…». Пустая строка = задача не продумана.
2. **Общее — интерфейс, частное — реализация.** Контракт живёт в общем слое: в `ui` — CompositionLocal (`LocalFolderPicker`, `LocalUriHandler`, `LocalBringToFront`), в `core` — порт или минимальный `expect/actual`. Реализации — в модуле точки входа (`app-desktop/…/picker`, `…/tray`).
3. **Выбор реализации — одна фабрика** (`defaultFolderPicker()`, `createTray()`): специфичное (портал, SNI, niri) → общее (AWT, `JFileChooser`) → `null`/no-op, и UI прячет функцию. No-op допустим только там, где нет ни специфичного, ни общего API; на desktop общий путь почти всегда есть (`SystemTray`/`TrayIcon.displayMessage`, `Desktop`, Swing). Цель, отложенная на потом, — отдельная задача в плане, а не «в долге». Проверки `os.name`/env — только в фабриках и конструкторах с параметрами по умолчанию (как `SystemBrowserOpener(osName, launch, browse)`), никогда в `core`/`ui`.
4. **Хост-специфичное — надстройка, не единственный путь.** Антипример: `CompositorFocus` работает только под niri, на Windows/GNOME молчит — пришлось перепроектировать (спека `uri-scheme-login`).
5. **Пути**: относительные пути vault — всегда `/` (`RemotePath`); пути ОС — только `Path`, без склейки строк; каталоги данных — только через `DaemonConfig` (`defaultConfigDir`). Новые имена файлов проверять на правила Windows.
6. **Внешние команды** (`ProcessBuilder`) — только в `app-desktop`, с тайм-аутом, перехватом `IOException` и запасным путём.

## Проверка

- Хост не доказывает кросс-платформенность. Собрать все цели модуля (`allTests`, `compileKotlinWasmJs`, Android при SDK) — см. `mimir-build-verify`.
- Windows/macOS проверяет CI-матрица (`ci.yml`): логика ветвления по ОС покрывается юнит-тестом с подставленным `osName`/`env`.
- В отчёте явно перечислить, что на каких платформах не проверено руками.

## Красные флаги

- «Под niri работает» без строк про Windows/Android/Docker.
- `os.name`, `System.getenv`, `ProcessBuilder`, `java.awt` в `core` или `ui/commonMain`.
- Linux-only библиотека в `commonMain` или без запасного пути.
- Захардкоженные `/home`, `~/.config`, `\n` в путях, `File.separator` в относительных путях vault.
- Функция, которой нет на платформе, но кнопка для неё видна.
- «На Windows пока no-op», когда есть AWT/JDK API.
