# Mimir

Один открытый инструмент синхронизации Obsidian-хранилища на всех платформах (Linux, Windows, Android). Он ощущается как Google Drive Desktop: один раз указал папку vault и пароль, дальше всё синхронизируется само. Локально лежат обычные `.md`, в Google Drive — только зашифрованные данные.

Шифруются и содержимое, и имена файлов (формат совместим с rclone crypt, имена шифруются по сегментам пути). Заметки в Drive нечитаемы для Gemini и любых сервисов с доступом к Drive. Запасной выход: vault можно расшифровать обычным rclone, даже если Mimir перестанет существовать.

Сейчас это **скелет**: модули собираются и запускаются, но реальной синхронизации, шифрования и OAuth нет — заглушки бросают `NotImplementedError`. Строка статуса до подключения: `Mimir: not connected`. Имя «Mimir» рабочее; все идентификаторы лежат в `gradle.properties`.

Спека: [docs/superpowers/specs/2026-10-23-mimir-design.md](docs/superpowers/specs/2026-10-23-mimir-design.md). Руководство для агентов: [AGENTS.md](AGENTS.md).

## Модули

| Модуль | Назначение |
|---|---|
| `build-logic` | convention-плагины Gradle, определение Android SDK |
| `core` | KMP (jvm, android, js, wasmJs): порты, реестр облаков, декоратор шифрования, заглушки |
| `drive` | адаптер Google Drive (заглушка) |
| `ui` | общий Compose-интерфейс для desktop, Android и web |
| `app-desktop` | запускалка для Windows и Linux, трей |
| `app-android` | запускалка для Android |
| `daemon` | headless-движок и локальный HTTP API (`127.0.0.1:47821`) |
| `web-ui` | сборка `ui` под wasmJs, раздаётся демоном |
| `obsidian-plugin` | плагин Obsidian на Kotlin/JS (работает и на телефоне) |
| `browser-extension` | расширение Chromium (MV3) на Kotlin/JS |
| `packaging/` | Arch PKGBUILD, Docker |

Зависимости идут только вниз: точки входа → `ui` → `drive` → `core`.

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

### niri

Окно десктопного приложения под niri требует `xwayland-satellite`. Трей пока не реализован (план: StatusNotifierItem); без трея приложение показывает окно как обычно.

### Google Cloud

Право `drive.file` привязано к **проекту Google Cloud**, а не к отдельному OAuth-клиенту. Все клиенты Mimir (desktop, Android, расширение браузера, web UI и демон) заводятся в одном проекте и видят одни и те же файлы. Запасной выход через rclone работает, если в rclone указать client ID того же проекта. Client ID и secret — в `mise.local.toml` при разработке и в GitHub Secrets при релизе.

## Релизы

Версия одна на все модули, управляется через changesets: изменение, влияющее на релиз, сопровождается `pnpm changeset`. Push в `main` ведёт PR «chore: version packages»; после слияния ставится тег `v<версия>` и запускается `release.yml`: MSI, APK, пакет Arch, zip плагина и расширения, образ Docker в GHCR, публикация в GitHub Release. Версию в `gradle.properties` руками не менять.

## Версии

- Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8.
- Откатов на JDK 25 нет.
- Версии библиотек в `gradle/libs.versions.toml` выставлены без проверки сборкой; при первой синхронизации их, возможно, придётся поднять.
- Linux: Arch, системная Java (`java-runtime>=27`). Windows: MSI per-user со вшитой JRE. Android: APK вне Google Play, `MANAGE_EXTERNAL_STORAGE`. Браузер: только Chromium, Manifest V3.
