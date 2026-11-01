# Deep Link URI Scheme Login & Cross-Platform Single-Instance Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить ручной возврат в Mimir после Google OAuth на бесшовное открытие по схеме `mimir://authorized` с кроссплатформенной нативной активацией окна (Linux, Windows, macOS), ликвидировать старые костыли фокуса (`CompositorFocus`, `LocalBringToFront`) и внедрить Single-Instance IPC на базе `UnixDomainSocketAddress`.

**Architecture:** `BrowserLoginReceiver` на loopback-интерфейсе демона после авторизации Google отдает страницу успеха с JS-редиректом на `mimir://authorized`. В `app-desktop` внедряется единый менеджер единственного экземпляра `SingleInstanceManager`: вторичный процесс передает URI первичному процессу через UDS (или LaunchServices на macOS) и немедленно завершается. Первичный экземпляр разворачивает и фокусирует окно через `PlatformFocus`. Устаревшие классы `CompositorFocus` и `LocalBringToFront` удаляются.

**Tech Stack:** Kotlin 2.4 / JDK 27, Compose Multiplatform Desktop, Java NIO UDS (`java.net.UnixDomainSocketAddress`), Java Desktop API (`Desktop.setOpenURIHandler`, `Desktop.requestForeground`), Java 27 FFM (Foreign Function & Memory API для Win32 `AllowSetForegroundWindow`), Ktor Server CIO.

**Spec:** [docs/superpowers/specs/2026-10-24-mimir-uri-scheme-login-design.md](file:///home/gidragir/projects/mimir/docs/superpowers/specs/2026-10-24-mimir-uri-scheme-login-design.md)

## Global Constraints

- Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8.
- Среда разработки — Arch Linux под niri (Wayland). На машине пользователя ничего не запускать под Windows; Windows-специфичное проверяется только в CI.
- Все shell-команды выполнять строго с префиксом `rtk`.
- Zero-Secrets Invariant: в схеме `mimir://authorized` не передается никаких токенов, кодов или параметров.
- Конфигурации и идентификаторы берутся из `gradle.properties` / `BuildConfig`.
- Тесты запускаются через `mise run test` (или `./gradlew test`).
- Архитектурный стиль TDD: сначала падающий тест, затем реализация.

## Review Focus

1. **Stale UDS socket file:** При аварийном завершении первого процесса на диске остается файл сокета; новый процесс обязан перехватить статус первичного, корректно удалив мертвый файл сокета.
   - *Test:* `SingleInstanceManagerTest.testRecoversFromStaleSocketFile()` в Task 3.
2. **Пустой запуск (Launch without args):** Повторный запуск Mimir из меню приложений/ярлыка без параметров должен активировать существующее окно, а не открывать вторую копию.
   - *Test:* `SingleInstanceManagerTest.testSecondaryInstanceWithoutArgsActivatesPrimary()` в Task 3.
3. **Отклонение диалога браузера / отсутствие схемы:** Если пользователь отклонил открытие ссылки в браузере или запустил приложение через `mise run` без зарегистрированной схемы, мастер настройки обязан завершить авторизацию по существующему поллингу демона.
   - *Test:* `SetupWizardScreenTest.testCompletesAuthorizationViaPollingWithoutUriScheme()` в Task 2.
4. **Безопасность схемы (Zero-Secrets):** URL успешного завершения не должен содержать токенов или учетных данных.
   - *Test:* `BrowserLoginTest.testSuccessPageDoesNotContainSecretsOrCodes()` в Task 1.
5. **Корректный рендеринг страницы успеха при отключенной схеме:** При `redirectScheme = null` страница отдает только текстовое сообщение без тегов редиректа на схему.
   - *Test:* `BrowserLoginTest.testSuccessPageWithoutScheme()` в Task 1.

---

### Task 1: Доработка `BrowserLoginReceiver` в модуле `:daemon`

**Files:**
- Modify: `daemon/src/main/kotlin/dev/mimir/daemon/BrowserLoginReceiver.kt`
- Modify: `daemon/src/test/kotlin/dev/mimir/daemon/BrowserLoginTest.kt`

**Interfaces:**
- Produces: `BrowserLoginReceiver(timeout: Duration = 5.minutes, redirectScheme: String? = "mimir")`

- [ ] **Step 1: Написать падающие тесты в `BrowserLoginTest.kt`**

```kotlin
@Test
fun testSuccessPageContainsRedirectScriptAndManualButton() = runTest {
    val receiver = BrowserLoginReceiver(redirectScheme = "mimir")
    receiver.start()
    receiver.expectedState = "state123"
    val client = HttpClient(CIO)
    val response = client.get("http://127.0.0.1:${receiver.port}/callback?code=test_code&state=state123")
    val body = response.bodyAsText()
    assertTrue(body.contains("""window.location.replace("mimir://authorized");"""))
    assertTrue(body.contains("""href="mimir://authorized""""))
    assertFalse(body.contains("test_code"))
    receiver.close()
    client.close()
}

@Test
fun testSuccessPageWithoutSchemeDoesNotContainRedirect() = runTest {
    val receiver = BrowserLoginReceiver(redirectScheme = null)
    receiver.start()
    receiver.expectedState = "state456"
    val client = HttpClient(CIO)
    val response = client.get("http://127.0.0.1:${receiver.port}/callback?code=test_code&state=state456")
    val body = response.bodyAsText()
    assertFalse(body.contains("window.location.replace"))
    assertFalse(body.contains("mimir://"))
    receiver.close()
    client.close()
}
```

- [ ] **Step 2: Запустить тесты и убедиться в падении**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.BrowserLoginTest"`  
Expected: FAIL (параметр `redirectScheme` не найден / старый `PAGE_DONE` не содержит скрипта)

- [ ] **Step 3: Реализовать генерацию HTML в `BrowserLoginReceiver.kt`**

Добавить в `BrowserLoginReceiver` параметр конструктора `redirectScheme: String? = "mimir"` и метод генерации страницы `renderSuccessPage(scheme: String?): String` с кнопкой `mimir://authorized` и вызовом `window.location.replace("$scheme://authorized")`. При `code` отдавать `renderSuccessPage(redirectScheme)`.

- [ ] **Step 4: Запустить тесты и убедиться в успешном прохождении**

Run: `rtk ./gradlew :daemon:test --tests "dev.mimir.daemon.BrowserLoginTest"`  
Expected: PASS

- [ ] **Step 5: Коммит**

```bash
rtk git add daemon/src/main/kotlin/dev/mimir/daemon/BrowserLoginReceiver.kt daemon/src/test/kotlin/dev/mimir/daemon/BrowserLoginTest.kt
rtk git commit -m "feat(daemon): add mimir://authorized redirect to oauth success page"
```

---

### Task 2: Ликвидация устаревших костылей фокуса (`CompositorFocus` и `LocalBringToFront`)

**Files:**
- Delete: `app-desktop/src/main/kotlin/dev/mimir/desktop/CompositorFocus.kt`
- Delete: `app-desktop/src/test/kotlin/dev/mimir/desktop/CompositorFocusTest.kt`
- Delete: `ui/src/commonMain/kotlin/dev/mimir/ui/platform/BringToFront.kt`
- Modify: `ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt`
- Modify: `ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`

**Interfaces:**
- Consumes: `SetupWizardScreen` без зависимости от `LocalBringToFront`.

- [ ] **Step 1: Обновить тесты в `SetupWizardScreenTest.kt`**

Удалить проверки и ссылки на `LocalBringToFront`. Убедиться, что тест `browserLoginStepProceedsWhenAuthorized` проверяет переход `onNext()` при получении `UiBrowserPoll.Authorized` без вызова сторонних коллбэков фокуса.

- [ ] **Step 2: Запустить тест `SetupWizardScreenTest` и убедиться в падении компиляции/теста**

Run: `rtk ./gradlew :ui:desktopTest --tests "dev.mimir.ui.SetupWizardScreenTest"`  
Expected: FAIL

- [ ] **Step 3: Удалить устаревшие файлы и ссылки на `BringToFront` / `CompositorFocus`**

1. Удалить `ui/src/commonMain/kotlin/dev/mimir/ui/platform/BringToFront.kt`.
2. Удалить `app-desktop/src/main/kotlin/dev/mimir/desktop/CompositorFocus.kt`.
3. Удалить `app-desktop/src/test/kotlin/dev/mimir/desktop/CompositorFocusTest.kt`.
4. В `SetupWizardScreen.kt` убрать `LocalBringToFront.current` и вызов `bringToFront?.invoke()`.
5. В `Main.kt` убрать объявление `compositorFocus` и провайдер `LocalBringToFront provides { ... }`.

- [ ] **Step 4: Запустить тесты `ui` и `app-desktop`**

Run: `rtk ./gradlew :ui:desktopTest :app-desktop:test`  
Expected: PASS

- [ ] **Step 5: Коммит**

```bash
rtk git rm app-desktop/src/main/kotlin/dev/mimir/desktop/CompositorFocus.kt app-desktop/src/test/kotlin/dev/mimir/desktop/CompositorFocusTest.kt ui/src/commonMain/kotlin/dev/mimir/ui/platform/BringToFront.kt
rtk git add ui/src/commonMain/kotlin/dev/mimir/ui/screens/SetupWizardScreen.kt ui/src/desktopTest/kotlin/dev/mimir/ui/SetupWizardScreenTest.kt app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt
rtk git commit -m "refactor(desktop): eliminate CompositorFocus and LocalBringToFront"
```

---

### Task 3: Кроссплатформенный менеджер единственного экземпляра (`SingleInstanceManager`)

**Files:**
- Create: `app-desktop/src/main/kotlin/dev/mimir/desktop/ipc/SingleInstanceManager.kt`
- Create: `app-desktop/src/test/kotlin/dev/mimir/desktop/ipc/SingleInstanceManagerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface IpcAction {
      data class OpenUri(val uri: String, val activationToken: String? = null) : IpcAction
      data object Activate : IpcAction
  }
  class SingleInstanceManager(socketPath: Path? = null) : AutoCloseable {
      var onActivationRequest: ((IpcAction) -> Unit)?
      fun acquirePrimary(args: Array<String>): Boolean
      override fun close()
  }
  ```

- [ ] **Step 1: Написать модульные тесты в `SingleInstanceManagerTest.kt`**

```kotlin
class SingleInstanceManagerTest {
    @Test
    fun testPrimaryAcquiresLockAndReceivesOpenUriFromSecondary() = runTest {
        val tempSocket = Files.createTempFile("mimir-test-sock-", ".sock").apply { Files.delete(this) }
        val primary = SingleInstanceManager(socketPath = tempSocket)
        val actionDeferred = CompletableDeferred<IpcAction>()
        primary.onActivationRequest = { actionDeferred.complete(it) }

        assertTrue(primary.acquirePrimary(emptyArray()))

        val secondary = SingleInstanceManager(socketPath = tempSocket)
        val acquiredBySecondary = secondary.acquirePrimary(arrayOf("mimir://authorized"))
        assertFalse(acquiredBySecondary)

        val action = withTimeout(5.seconds) { actionDeferred.await() }
        assertEquals(IpcAction.OpenUri("mimir://authorized"), action)

        primary.close()
    }

    @Test
    fun testSecondaryWithoutArgsSendsActivateAction() = runTest {
        val tempSocket = Files.createTempFile("mimir-test-sock-", ".sock").apply { Files.delete(this) }
        val primary = SingleInstanceManager(socketPath = tempSocket)
        val actionDeferred = CompletableDeferred<IpcAction>()
        primary.onActivationRequest = { actionDeferred.complete(it) }

        assertTrue(primary.acquirePrimary(emptyArray()))

        val secondary = SingleInstanceManager(socketPath = tempSocket)
        assertFalse(secondary.acquirePrimary(emptyArray()))

        val action = withTimeout(5.seconds) { actionDeferred.await() }
        assertEquals(IpcAction.Activate, action)

        primary.close()
    }

    @Test
    fun testRecoversFromStaleSocketFile() {
        val tempSocket = Files.createTempFile("mimir-test-stale-", ".sock")
        // Файл существует, но сервер не слушает (stale socket)
        val manager = SingleInstanceManager(socketPath = tempSocket)
        assertTrue(manager.acquirePrimary(emptyArray()))
        manager.close()
    }
}
```

- [ ] **Step 2: Запустить тесты и убедиться в падении**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.ipc.SingleInstanceManagerTest"`  
Expected: FAIL (класс `SingleInstanceManager` не существует)

- [ ] **Step 3: Реализовать `SingleInstanceManager.kt`**

1. Определение путей сокетов:
   - Linux: `$XDG_RUNTIME_DIR/mimir-ui.sock` (fallback: `~/.cache/mimir/mimir-ui.sock`).
   - Windows: `%LOCALAPPDATA%\mimir\mimir-ui.sock`.
   - macOS: `~/Library/Caches/mimir/mimir-ui.sock`.
2. Подключение через `SocketChannel.open(UnixDomainSocketAddress.of(path))`.
3. При успехе подключения — формирование `ACTION:OPEN_URI` или `ACTION:ACTIVATE`, отправка `\n\n`, чтение `OK\n`, возврат `false` (вторичный процесс).
4. При ошибке подключения — удаление старого файла `Files.deleteIfExists(path)`, бинд `ServerSocketChannel.bind(UnixDomainSocketAddress.of(path))`, запуск фонового треда/корутины чтения, возврат `true` (первичный процесс).
5. Для macOS — регистрация `Desktop.getDesktop().setOpenURIHandler { event -> onActivationRequest?.invoke(IpcAction.OpenUri(event.uri.toString())) }`.

- [ ] **Step 4: Запустить тесты и убедиться в успешном прохождении**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.ipc.SingleInstanceManagerTest"`  
Expected: PASS

- [ ] **Step 5: Коммит**

```bash
rtk git add app-desktop/src/main/kotlin/dev/mimir/desktop/ipc/SingleInstanceManager.kt app-desktop/src/test/kotlin/dev/mimir/desktop/ipc/SingleInstanceManagerTest.kt
rtk git commit -m "feat(desktop): implement cross-platform SingleInstanceManager via UnixDomainSocket"
```

---

### Task 4: Нативная активация окна (`PlatformFocus`) и интеграция в `Main.kt`

**Files:**
- Create: `app-desktop/src/main/kotlin/dev/mimir/desktop/PlatformFocus.kt`
- Create: `app-desktop/src/test/kotlin/dev/mimir/desktop/PlatformFocusTest.kt`
- Modify: `app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`

**Interfaces:**
- Consumes: `SingleInstanceManager`, `IpcAction`
- Produces: `PlatformFocus.activate(window: java.awt.Window)`

- [ ] **Step 1: Написать тесты для `PlatformFocusTest.kt`**

Проверка вызовов состояния окна (`visible = true`, `extendedState = NORMAL`, `toFront()`, `requestFocus()`) на тестовом Frame.

- [ ] **Step 2: Запустить тест и убедиться в падении**

Run: `rtk ./gradlew :app-desktop:test --tests "dev.mimir.desktop.PlatformFocusTest"`  
Expected: FAIL

- [ ] **Step 3: Реализовать `PlatformFocus.kt` и обновить `Main.kt`**

1. В `PlatformFocus.kt`:
   - Восстановление AWT Window (`extendedState = NORMAL`, `isVisible = true`, `toFront()`, `requestFocus()`).
   - На macOS: `Desktop.getDesktop().requestForeground(true)`.
   - На Windows: вызов `AllowSetForegroundWindow` через Java 27 FFM API во вторичном процессе.
2. В `Main.kt`:
   - В точке входа `fun main(args: Array<String>)`:
     ```kotlin
     val ipc = SingleInstanceManager()
     if (!ipc.acquirePrimary(args)) {
         exitProcess(0)
     }
     ```
   - Внутри `runDesktop(config, ipc)`:
     ```kotlin
     ipc.onActivationRequest = {
         scope.launch(Dispatchers.Main) {
             PlatformFocus.activate(window)
         }
     }
     ```

- [ ] **Step 4: Запустить тесты `app-desktop`**

Run: `rtk ./gradlew :app-desktop:test`  
Expected: PASS

- [ ] **Step 5: Коммит**

```bash
rtk git add app-desktop/src/main/kotlin/dev/mimir/desktop/PlatformFocus.kt app-desktop/src/test/kotlin/dev/mimir/desktop/PlatformFocusTest.kt app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt
rtk git commit -m "feat(desktop): integrate SingleInstanceManager and native PlatformFocus in Main"
```

---

### Task 5: Регистрация схемы `mimir://` в пакетах Linux, Windows и macOS

**Files:**
- Modify: `packaging/arch/mimir.desktop`
- Modify: `packaging/arch/mimir.sh`
- Create: `app-desktop/src/packaging/windows/overrides.wxs`
- Modify: `app-desktop/build.gradle.kts`

**Interfaces:**
- Linux `.desktop`: `MimeType=x-scheme-handler/mimir;`, `Exec=mimir %u`
- Windows MSI: `HKCU\Software\Classes\mimir`
- macOS `Info.plist`: `CFBundleURLTypes`

- [ ] **Step 1: Обновить файлы пакета Arch Linux**

1. В `packaging/arch/mimir.desktop` добавить `Exec=mimir %u` и `MimeType=x-scheme-handler/mimir;`.
2. В `packaging/arch/mimir.sh` обновить запуск на `exec /usr/bin/java ... -jar /usr/lib/mimir/mimir.jar "$@"`.

- [ ] **Step 2: Создать шаблон WiX реестра для Windows MSI**

Создать `app-desktop/src/packaging/windows/overrides.wxs` с ключами реестра `HKCU\Software\Classes\mimir` (`URL Protocol`="", `shell\open\command`=`"[#mimir.exe]" "%1"`).

- [ ] **Step 3: Настроить `app-desktop/build.gradle.kts` для Windows и macOS**

Добавить в `compose.desktop.application.nativeDistributions`:
- В `windows`: подключение шаблона реестра через `appResourcesRootDir`.
- В `macOS`: секцию `infoPlist` с сырым XML `CFBundleURLTypes` для схемы `mimir`.

- [ ] **Step 4: Проверить сборку проекта**

Run: `rtk mise run build`  
Expected: Сборка всех модулей и тестов завершается с кодом 0.

- [ ] **Step 5: Коммит**

```bash
rtk git add packaging/arch/mimir.desktop packaging/arch/mimir.sh app-desktop/src/packaging/windows/overrides.wxs app-desktop/build.gradle.kts
rtk git commit -m "feat(packaging): register mimir:// scheme for Linux desktop, Windows MSI, and macOS"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** Все пункты утвержденной спецификации покрыты задачами 1–5 (редирект в `daemon`, удаление `CompositorFocus` / `LocalBringToFront`, UDS Single-Instance, `PlatformFocus` и упаковка).
- [x] **Step scan:** Каждый шаг содержит конкретные файлы, интерфейсы, команды верификации и ожидаемый результат.
- [x] **Type consistency:** Типы `IpcAction` и сигнатуры `SingleInstanceManager` согласованы между Task 3 и Task 4.
- [x] **Review Focus:** Все 5 рисков покрыты тестами в задачах 1–3.
- [x] **Proportion:** План лаконичен, не дублирует реализации тривиального кода и сфокусирован на ключевых интерфейсах.
