# Mimir

Один открытый инструмент синхронизации Obsidian-хранилища на всех платформах (Linux, Windows, Android). Он ощущается как Google Drive Desktop: один раз указал папку vault и пароль, дальше всё синхронизируется само. Локально лежат обычные `.md`, в Google Drive — только зашифрованные данные.

Шифруются и содержимое, и имена файлов (формат совместим с rclone crypt, имена шифруются по сегментам пути). Заметки в Drive нечитаемы для Gemini и любых сервисов с доступом к Drive. Запасной выход: vault можно расшифровать обычным rclone, даже если Mimir перестанет существовать.

Сейчас это **скелет с готовым шифрованием**: модули собираются и запускаются, шифр (`RcloneCipher`) реализован и совместим с rclone, но реальной синхронизации и OAuth нет — заглушки бросают `NotImplementedError`. Строка статуса до подключения: `Mimir: not connected`. Имя «Mimir» рабочее; все идентификаторы лежат в `gradle.properties`.

Спека: [docs/superpowers/specs/2026-10-23-mimir-design.md](docs/superpowers/specs/2026-10-23-mimir-design.md). Руководство для агентов: [AGENTS.md](AGENTS.md).

## Модули

| Модуль | Назначение |
|---|---|
| `build-logic` | convention-плагины Gradle, определение Android SDK |
| `libs/core` | KMP (jvm, android, js, wasmJs): порты, реестр облаков, декоратор шифрования, `RcloneCipher`, заглушки |
| `libs/drive` | адаптер Google Drive (заглушка) |
| `libs/ui` | общий Compose-интерфейс для desktop, Android и web |
| `apps/desktop` | запускалка для Windows и Linux, трей |
| `apps/android` | запускалка для Android |
| `libs/client` | HTTP-клиент, DTO и интерфейс удалённого демона |
| `libs/runtime` | общая логика демона и Android (KMP jvm/android): движок, вход в Google, база состояния, планировщик |
| `apps/daemon` | headless-движок и локальный HTTP API (`127.0.0.1:47821`) |
| `apps/web-ui` | сборка `libs/ui` под wasmJs, раздаётся демоном |
| `apps/obsidian-plugin` | плагин Obsidian на Kotlin/JS (работает и на телефоне) |
| `apps/browser-extension` | расширение Chromium (MV3) на Kotlin/JS |
| `packaging/` | Arch PKGBUILD, Docker |

Зависимости идут только вниз: точки входа → `libs/ui` → `libs/drive` → `libs/core`.

## Скачивание и установка

Готовые релизные сборки для всех поддерживаемых платформ (Windows MSI, Android APK, Linux пакеты, Chromium Extension) доступны на странице [GitHub Releases](https://github.com/gidragir/mimir/releases).

### Цифровая подпись (Code Signing)

Бесплатная подпись кода для релизов Windows предоставлена сервисом [SignPath.io](https://signpath.io), сертификат предоставлен [SignPath Foundation](https://signpath.org).

Free code signing provided by [SignPath.io](https://signpath.io), certificate by [SignPath Foundation](https://signpath.org).

## Как начать

```sh
mise install
cp mise.local.toml.example mise.local.toml   # секреты нужны только для реального входа в Google
mise run build                               # сборка и все тесты
mise run test                                # только тесты
```

Запуск точек входа:

| Что | Команда |
|---|---|
| Десктопное приложение | `mise run desktop` |
| Android (устройство подключено) | `mise run android` |
| Демон | `mise run daemon` |
| Web UI в режиме разработки | `mise run web` |
| Плагин Obsidian | `mise run plugin` |
| Расширение браузера | `mise run extension` |
| MSI (только Windows) | `mise run package:msi` |
| Uber-jar для пакета Arch | `mise run package:arch` |
| Docker-образ демона | `mise run package:docker` |

Демон в Docker:

```sh
docker run -d --rm -p 127.0.0.1:47821:47821 -v "$HOME/vault:/vault" mimir:dev
curl -s 127.0.0.1:47821/health
```

Если порт занят, демон завершается с кодом 2 и сообщением `Port <port> on <bind> is already in use`.

### Android SDK

Необязателен. Без него `mise run build` проходит, пропуская Android-таргеты с предупреждением `Android SDK not found: skipping Android targets`. Чтобы собирать Android, задайте `ANDROID_HOME` (в `mise.local.toml` или в окружении).

### Браузер для js-тестов

Тесты `libs/core` и `libs/drive` на таргете js идут в headless Chromium (Karma). Если Chrome/Chromium не находится сам, укажите путь: `CHROME_BIN=/usr/bin/chromium` (в `mise.local.toml` — см. `mise.local.toml.example` — или в окружении). Тесты wasmJs идут в Node.js: в Karma+Chromium общий для wasm klib падает на `import.meta`.

### Что проверяется только с SDK или в CI

Задачи `:apps:android:*` (в том числе `ManifestPermissionsTest` и `assembleDebug`) выполняются только при наличии Android SDK (проверены на Arch с SDK из Android Studio: `ANDROID_HOME=~/Android/Sdk`); без SDK они пропускаются, в CI на Linux идут с `setup-android`. MSI (`package:msi`) собирается и проверяется только на Windows-раннере в CI. Остальное (сборка, тесты, Docker, пакет Arch) проверяется на Arch.

### Android

- **Требования:** Android 8.0+ (API 26+). Установка APK вне Google Play (sideloading).
- **Разрешения:** `MANAGE_EXTERNAL_STORAGE` («Доступ ко всем файлам») для чтения и записи локального хранилища Obsidian, `POST_NOTIFICATIONS` (Android 13+) для системных уведомлений о состоянии синхронизации.
- **Настройка Google OAuth (Google Identity Services):**
  - Android-клиенты заводятся в том же проекте Google Cloud Platform, где созданы остальные клиенты (единый scope `drive.file`).
  - Тип клиента — «Android», package name — `dev.mimir.android`.
  - В консоли GCP указываются SHA-1 фингерпринты для используемых ключей подписи (debug и release).
  - Команда для получения SHA-1 debug-ключа:
    ```sh
    keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
    ```
  - При отсутствии Google Play Services или ненастроенном GIS-клиенте приложение прозрачно переключается на вход через браузер (Custom Tabs / системный браузер с перенаправлением на `mimir://authorized`).

### niri

Окно десктопного приложения под niri требует `xwayland-satellite`. Лаунчеры Linux (`mise run desktop`, `packaging/arch/mimir.sh`) выставляют `_JAVA_AWT_WM_NONREPARENTING=1`: без этого Compose под niri рисует только левый верхний угол окна. Трей — StatusNotifierItem по D-Bus.

### Google Cloud (для владельца проекта)

Право `drive.file` привязано к **проекту Google Cloud**, а не к отдельному OAuth-клиенту. Все клиенты Mimir (desktop, Android, расширение браузера, web UI и демон) заводятся в одном проекте и видят одни и те же файлы.

1. **Branding.** На экране согласия задайте App name = `Mimir`, иначе Google покажет прежнее имя проекта.
2. **Два OAuth-клиента в одном проекте.** Тип «Desktop app» нужен для входа кнопкой через браузер (loopback + PKCE); тип «TVs and Limited Input devices» — для Device Code (Docker-демон, вход с другого устройства). Google не позволяет использовать один клиент для обоих способов, а refresh-токен обновляется только тем клиентом, который его выдал.
3. **Publishing status — «In production».** Право `drive.file` нечувствительное, security-аудит не нужен; достаточно пройти проверку бренда. В статусе «Testing» войти могут только добавленные тестовые пользователи, а refresh-токены истекают через 7 дней.
4. **Файлы rclone.** Файлы, созданные rclone с другим client ID, под `drive.file` Mimir не видит. Чтобы работать с ними вместе, укажите в rclone client ID этого же проекта.
5. **Секреты релиза.** Client ID и secret кладутся в `mise.local.toml` при разработке и в GitHub Secrets при релизе: `MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_ID`, `MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_SECRET`, `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_ID`, `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_SECRET`. Значения вшиваются в BuildConfig модуля `apps/daemon` и попадают в сборки msi, arch и apk; без них сборка и тесты проходят, а вход в Google в такой сборке недоступен. Секрет Desktop-клиента у устанавливаемого приложения секретом не является (защита — PKCE и согласие пользователя).

### Свой OAuth-клиент

Продвинутые пользователи могут подставить свой клиент через переменные окружения демона или одноимённые ключи `config.json`; они приоритетнее вшитых значений:

| Переменные | Для чего |
|---|---|
| `MIMIR_GOOGLE_DESKTOP_CLIENT_ID`, `MIMIR_GOOGLE_DESKTOP_CLIENT_SECRET` | вход через браузер (клиент «Desktop app») |
| `MIMIR_GOOGLE_DEVICE_CLIENT_ID`, `MIMIR_GOOGLE_DEVICE_CLIENT_SECRET` | вход по коду (клиент «TVs and Limited Input devices») |

Старые `MIMIR_GOOGLE_CLIENT_ID` / `MIMIR_GOOGLE_CLIENT_SECRET` читаются как алиас Device-пары с предупреждением в логе. Демон в Docker не получает вшитых значений: он настраивается только этими переменными (вход по Device Code), например `-e MIMIR_GOOGLE_DEVICE_CLIENT_ID=... -e MIMIR_GOOGLE_DEVICE_CLIENT_SECRET=...`.

### Проверка обновлений

Демон раз в сутки (первый раз через 30 секунд после старта) смотрит релизы GitHub; состояние отдают `GET /api/v1/update` и `POST /api/v1/update/{check,download,apply}`. В Docker и других headless-запусках демон только пишет в лог `Update vX.Y.Z available: <ссылка>`, образ обновляет оператор. Отключить проверку: переменная `MIMIR_UPDATE_CHECK=false` (также `0`, `off`, `no`; по умолчанию включена) или ключ `"updateCheck": false` в `config.json`; тогда статус — `DISABLED`, в сеть демон не ходит.

## Совместимость с rclone

`RcloneCipher` (модуль `libs/core`, чистый Kotlin на jvm, android, js и wasmJs) бит-в-бит совместим с rclone crypt в режиме `filename_encryption = standard`, `directory_name_encryption = true`, `filename_encoding = base32`:

- Ключи: `scrypt(password, salt, N=16384, r=8, p=1, dkLen=80)`; соль — `password2` или стандартная соль rclone. Байты 0..31 — ключ данных, 32..63 — ключ имён, 64..79 — tweak имён.
- Файл: `RCLONE\0\0` + nonce (24 байта) + блоки до 64 КиБ открытого текста в NaCl `secretbox` (XSalsa20-Poly1305, +16 байт тега на блок). Пустой файл — 32 байта заголовка.
- Имя: каждый сегмент пути отдельно — UTF-8, PKCS#7 до 16 байт, EME на AES-256, base32hex в нижнем регистре без `=`.

Расшифровать копию vault вручную (путь — локальная копия зашифрованной папки из Drive, пароль и соль — те же, что вводились в Mimir; `rclone obscure` превращает пароль в формат конфига rclone):

```bash
rclone copy ":crypt,remote=/путь/к/копии/из/Drive,password=$(rclone obscure 'пароль')[,password2=$(rclone obscure 'соль')]:" ./vault-plain
```

Оговорки (общие с rclone): размеры и время изменения файлов в Drive остаются видны; усечение файла ровно по границе блока не обнаруживается — у формата нет финального маркера. Первое получение ключей (scrypt) на js и wasm занимает порядка секунды, в проверках этапа — не дольше нескольких секунд вместе с запуском браузера. Golden-векторы лежат в `libs/core/src/commonTest/kotlin/dev/mimir/core/RcloneVectors.kt`; их генерирует `scripts/gen-rclone-vectors.sh` (нужен rclone ≥ 1.60), а опциональный `RcloneInteropTest` (jvm) сверяется с живым rclone, если он есть в `PATH`.

## Релизы

Версия одна на все модули (`mimir.version` в `gradle.properties`) и управляется Release Please: изменения коммитятся по Conventional Commits, push в `main` ведёт PR «chore: release v<версия>»; после слияния ставится тег `v<версия>` и вызывается `release.yml`: MSI, APK, пакет Arch, zip плагина и расширения, образ Docker в GHCR, прикрепление артефактов к GitHub Release. Версию в `gradle.properties` руками не менять.

## Версии

- Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8.
- AGP 9.5.0 (9.1.0 не работает на JDK 27: его `jlink`-трансформация падает с «cannot find the build signature»), `compileSdk` 37 (требуют зависимости Compose 1.12). Откатов на JDK 25 нет. Но Kotlin 2.4 генерирует байткод максимум для JVM 26, поэтому в JVM-модулях `javac` запускается с `--release 26` (на JDK 27).
- Версии библиотек в `gradle/libs.versions.toml` выставлены без проверки сборкой; при первой синхронизации их, возможно, придётся поднять.
- Linux: Arch, системная Java (`java-runtime>=27`). Windows: MSI per-user со вшитой JRE. Android: APK вне Google Play, `MANAGE_EXTERNAL_STORAGE`. Браузер: только Chromium, Manifest V3.
