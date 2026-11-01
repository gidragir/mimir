# Mimir: Deep Link URI Scheme Login & Cross-Platform Single-Instance Activation

**Дата:** 2026-10-24  
**Статус:** Approved (после Builder Check, добавления macOS и удаления старых костылей фокуса)  
**Область:** `daemon`, `app-desktop`, `ui`, `packaging`

---

## 1. Контекст и мотивация

Текущий сценарий авторизации Mimir через Google Drive использует браузерный вход (PKCE + loopback-редирект на `127.0.0.1` на случайном свободном порту). После подтверждения входа Google редиректит пользователя на страницу локального сервера `BrowserLoginReceiver`, где выводится статичный текст «Готово! Можно вернуться в Mimir».

Это создает две проблемы:
1. **Разрыв пользовательского пути (UX friction):** Пользователь вынужден вручную закрывать вкладку браузера, искать и переключаться на окно Mimir.
2. **Фрагментация и платформенные костыли возврата фокуса:**
   - В UI существует интерфейс `LocalBringToFront.kt`, который вызывается при завершении поллинга в мастере настройки (`SetupWizardScreen.kt`).
   - В `app-desktop` для niri добавлен класс `CompositorFocus.kt`, который дергает CLI-утилиту `niri msg action focus-window`. Это платформозависимый временный обходной путь только для niri, который не работает под другими Wayland-композиторами (GNOME, KDE, Sway), не масштабируется на Windows и macOS и размывает ответственность UI.

Решение: полностью ликвидировать `CompositorFocus.kt` и `LocalBringToFront.kt`, заменив их на системный механизм открытия приложения по схеме `mimir://authorized` с нативной активацией окна средствами ОС (LaunchServices на macOS, foreground transfer на Windows, standard xdg single-instance активация на Linux).

---

## 2. Цели, не-цели и анти-цели

### Цели
- **Бесшовный возврат в приложение:** Браузер после авторизации Google автоматически инициирует возврат в Mimir по схеме `mimir://authorized`.
- **Полная ликвидация устаревших костылей фокуса:** Полное удаление `CompositorFocus.kt`, `CompositorFocusTest.kt` и `LocalBringToFront.kt`. Фокус окна больше не инициируется изнутри Compose UI через опрос.
- **Кроссплатформенность Linux, Windows и macOS:** Одинаково надежная работа под Linux (Wayland/X11), Windows (10+) и macOS (12+).
- **Zero-Secrets URI Protocol:** Полная защита от перехвата токенов: в схеме передается исключительно триггер завершения авторизации без токенов, кодов и секретов.
- **Single-Instance IPC:** 
  - На **Linux и Windows**: надежный механизм единственного экземпляра `app-desktop` на базе стандартного Java 16+ `UnixDomainSocketAddress` (AF_UNIX). Вторичный процесс передает команду первому и мгновенно завершается.
  - На **macOS**: поддержка системного LaunchServices через `java.awt.Desktop.setOpenURIHandler`.
- **Поддержка повторного запуска без аргументов:** Запуск вторичного экземпляра без параметров (клик по иконке в меню приложений/доке) активирует и выводит существующее окно на передний план.
- **Нативная активация окна (`PlatformFocus`):**
  - **Linux:** Вторичный процесс передает команду первому экземпляру; первичный экземпляр восстанавливает окно (`extendedState = NORMAL`, `toFront()`, `requestFocus()`).
  - **Windows:** Вторичный процесс передает права фокуса через `AllowSetForegroundWindow` (Java 27 FFM API) перед завершением.
  - **macOS:** Вызов `Desktop.getDesktop().requestForeground(true)` + `window.toFront()`.
- **Декларативная регистрация в дистрибутивах:** Ассоциация схемы в `mimir.desktop` (Arch Linux), WiX-шаблон для Windows MSI и `Info.plist` (`CFBundleURLTypes`) для macOS.
- **Graceful Fallback:** Если схема не зарегистрирована (запуск через `mise run`) или диалог браузера отклонен, мастер настройки не блокируется и завершает шаг штатно по существующему поллингу.

### Не-цели
- Отказ от loopback-сервера в пользу прямого `redirect_uri=mimir://...`: Google OAuth строго запрещает кастомные схемы для клиентов типа «Desktop» (RFC 8252).
- Внедрение внешнего прокси-сервера Mimir в интернете.
- Изменение механизмов авторизации на Android, в браузерном расширении и в Obsidian-плагине.

### Анти-цели
- Сохранение вызова команд `niri msg` и класса `CompositorFocus`.
- Передача секретов, авторизационных кодов или токенов через параметры схемы.
- Загрязнение реестра или пользовательских каталогов при локальном запуске (`mise run`).
- Создание дублирующих окон или повторный запуск демона при клике на ссылку или повторном старте.

---

## 3. Архитектурные решения и рассмотренные подходы

### Вариант 1 (Выбран): Единая модель нативной активации по схеме `mimir://` с удалением `CompositorFocus`
- **Принцип:**
  1. `BrowserLoginReceiver` принимает OAuth callback от Google на `http://127.0.0.1:<port>/callback`, завершает обмен токенов с PKCE и отдает страницу успеха с JavaScript-редиректом `window.location.replace("mimir://authorized")`.
  2. ОС вызывает обработчик схемы `mimir://authorized`.
  3. `app-desktop` обрабатывает вызов:
     - На Linux/Windows: вторичный процесс через UDS передает команду первичному и завершается.
     - На macOS: LaunchServices направляет AppleEvent в работающий процесс через `Desktop.setOpenURIHandler`.
  4. Первичный процесс через `PlatformFocus` выводит окно на передний план.
  5. Классы `CompositorFocus.kt`, `CompositorFocusTest.kt` и `LocalBringToFront.kt` удаляются из репозитория. В `SetupWizardScreen` убирается вызов `bringToFront?.invoke()`.
  6. Если вызов схемы не произошел (отмена в браузере или запуск из `mise run`), существующий поллинг демона в `SetupWizardScreen` гарантирует бесшовный переход к следующему шагу при ручном возврате.
- **Обоснование:** Устранение платформенных костылей, чистое разделение ответственности, соответствие спецификациям Google OAuth и OS Desktop standards.

---

## 4. Детальная спецификация компонентов

### 4.1. Демон: генерация страницы успеха в `BrowserLoginReceiver.kt`

В конструктор `BrowserLoginReceiver` добавляется опциональный параметр схемы `redirectScheme: String? = "mimir"`.

```kotlin
class BrowserLoginReceiver(
    private val timeout: Duration = 5.minutes,
    private val redirectScheme: String? = "mimir",
) : AutoCloseable { ... }
```

HTML-ответ при успешной авторизации (`PAGE_DONE`):
```kotlin
private fun renderSuccessPage(scheme: String?): String {
    val redirectScript = if (scheme != null) {
        """<script>window.location.replace("$scheme://authorized");</script>"""
    } else ""
    val manualButton = if (scheme != null) {
        """<a href="$scheme://authorized" class="btn">Открыть Mimir</a>"""
    } else ""

    return """<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <title>Mimir — Вход выполнен</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
           display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; 
           background: #1e1e1e; color: #dcddde; }
    .card { background: #262626; padding: 32px; border-radius: 8px; border: 1px solid #333; text-align: center; max-width: 400px; }
    h1 { font-size: 20px; margin-bottom: 12px; color: #ffffff; }
    p { font-size: 14px; color: #999; line-height: 1.5; margin-bottom: 24px; }
    .btn { display: inline-block; background: #705dcf; color: white; padding: 10px 20px; 
           border-radius: 6px; text-decoration: none; font-weight: 500; font-size: 14px; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Готово!</h1>
    <p>Авторизация успешно завершена. Перенаправляем вас обратно в приложение Mimir…</p>
    $manualButton
  </div>
  $redirectScript
</body>
</html>"""
}
```

---

### 4.2. Механизм единственного экземпляра (`SingleInstanceManager`) в `app-desktop`

Создается кроссплатформенный класс `dev.mimir.desktop.ipc.SingleInstanceManager`:
- На Linux и Windows использует стандартный `java.net.UnixDomainSocketAddress` (Java 16+).
- На macOS регистрирует системный `java.awt.Desktop.getDesktop().setOpenURIHandler`.

#### Расположение сокета:
- **Linux:** `$XDG_RUNTIME_DIR/mimir-ui.sock` (fallback: `~/.cache/mimir/mimir-ui.sock`).
- **Windows:** `%LOCALAPPDATA%\mimir\mimir-ui.sock`.
- **macOS:** `~/Library/Caches/mimir/mimir-ui.sock` (fallback: `$TMPDIR/mimir-ui.sock`).

#### Формат IPC-сообщений:
```
ACTION:OPEN_URI
URI:mimir://authorized
XDG_ACTIVATION_TOKEN:<token_or_empty>
END
```
или для запуска без аргументов:
```
ACTION:ACTIVATE
END
```

---

### 4.3. Активация окна (`PlatformFocus`) и ликвидация старых механизмов

1. **Полное удаление старых компонентов:**
   - Удаляется `app-desktop/src/main/kotlin/dev/mimir/desktop/CompositorFocus.kt`.
   - Удаляется `app-desktop/src/test/kotlin/dev/mimir/desktop/CompositorFocusTest.kt`.
   - Удаляется `ui/src/commonMain/kotlin/dev/mimir/ui/platform/BringToFront.kt`.
   - В `SetupWizardScreen.kt` удаляется инжекция и вызов `LocalBringToFront`.
   - В `SetupWizardScreenTest.kt` удаляется мок `LocalBringToFront`.

2. **Новый класс `PlatformFocus` в `app-desktop`:**
   ```kotlin
   object PlatformFocus {
       fun activate(window: java.awt.Window) {
           window.isVisible = true
           if (window is java.awt.Frame) {
               window.extendedState = java.awt.Frame.NORMAL
           }
           window.toFront()
           window.requestFocus()

           when {
               isMac() -> {
                   if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                       Desktop.getDesktop().requestForeground(true)
                   }
               }
               isWindows() -> {
                   // Foreground lock уже передан вторичным процессом через AllowSetForegroundWindow
               }
               else -> {
                   // Linux / Wayland / X11: вызов toFront() в контексте IPC-активации
               }
           }
       }
   }
   ```

3. **Интеграция в `Main.kt`:**
   ```kotlin
   fun main(args: Array<String>) {
       val ipcManager = SingleInstanceManager()
       if (!ipcManager.acquirePrimary(args)) {
           // Вторичный процесс: команда передана первичному через UDS
           exitProcess(0)
       }
       
       val config = DaemonConfig.load()
       runDesktop(config, ipcManager)
   }
   ```
   В `runDesktop`:
   ```kotlin
   ipcManager.onActivationRequest = { action ->
       scope.launch(Dispatchers.Main) {
           PlatformFocus.activate(window)
       }
   }
   ```

---

### 4.4. Регистрация схемы в дистрибутивах

#### Arch Linux (`packaging/arch`):
1. В `packaging/arch/mimir.desktop`:
   ```ini
   [Desktop Entry]
   Type=Application
   Name=Mimir
   Comment=Obsidian vault sync with encryption over Google Drive
   Exec=mimir %u
   Icon=mimir
   Terminal=false
   Categories=Utility;
   MimeType=x-scheme-handler/mimir;
   ```
2. В `packaging/arch/mimir.sh`:
   ```bash
   #!/bin/sh
   export _JAVA_AWT_WM_NONREPARENTING=1
   exec /usr/bin/java -jar /usr/lib/mimir/mimir.jar "$@"
   ```

#### Windows MSI (`app-desktop` packaging):
Шаблон WiX `app-desktop/src/packaging/windows/overrides.wxs`:
```xml
<RegistryKey Root="HKCU" Key="Software\Classes\mimir">
    <RegistryValue Type="string" Value="URL:Mimir Protocol" />
    <RegistryValue Type="string" Name="URL Protocol" Value="" />
    <RegistryKey Key="shell\open\command">
        <RegistryValue Type="string" Value="&quot;[#mimir.exe]&quot; &quot;%1&quot;" />
    </RegistryKey>
</RegistryKey>
```

#### macOS (`app-desktop/build.gradle.kts`):
В блоке `nativeDistributions.macOS` для `Info.plist`:
```kotlin
macOS {
    bundleID = providers.gradleProperty("mimir.appId").get()
    infoPlist {
        extraKeysRawXml = """
            <key>CFBundleURLTypes</key>
            <array>
                <dict>
                    <key>CFBundleURLName</key>
                    <string>Mimir Protocol</string>
                    <key>CFBundleURLSchemes</key>
                    <array>
                        <string>mimir</string>
                    </array>
                </dict>
            </array>
        """.trimIndent()
    }
}
```

---

## 5. План тестирования и верификации

### 5.1. Автоматизированные тесты (Unit & Integration)
1. **`BrowserLoginReceiverTest` (`daemon`):**
   - Проверка генерации HTML: валидация редиректа на `mimir://authorized` и кнопки.
   - Проверка отключения редиректа при `redirectScheme = null`.
2. **`SingleInstanceManagerTest` (`app-desktop`):**
   - Тестирование передачи `OPEN_URI` и `ACTIVATE` через UDS-сокет.
   - Проверка очистки stale-сокета.
3. **`PlatformFocusTest` (`app-desktop`):**
   - Проверка вызова методов активации окна для разных платформ.
4. **Обновление `SetupWizardScreenTest` (`ui`):**
   - Удаление проверок `LocalBringToFront`.
   - Проверка штатного перехода шага мастера при авторизации.

### 5.2. Ручная верификация
- **Arch Linux под niri:**
  1. `mise run build` проходит без предупреждений и ошибок.
  2. Запуск приложения: `rtk mise run :app-desktop:run`.
  3. Тестовый вызов: `rtk ./gradlew :app-desktop:run --args="mimir://authorized"`.
  4. Проверка: второй процесс мгновенно завершается, окно primary-приложения восстанавливается и активируется.
  5. Полный цикл авторизации через Google: диалог браузера и автоматический возврат.
- **Windows / macOS (CI):**
  1. Сборка пакетов (MSI на Windows, DMG на macOS).
  2. Проверка валидности генерируемых манифестов (`Info.plist`, WiX Registry).
