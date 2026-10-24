# Mimir Desktop Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Десктоп Mimir, которым может пользоваться не программист: вход в Google одной кнопкой, корректное окно под niri, подробный и понятный прогресс синхронизации.

**Architecture:** Вход через браузер (loopback + PKCE) живёт в `DaemonService` рядом с Device Code: чистая логика в `drive/commonMain`, одноразовый HTTP-приёмник в `daemon`. Прогресс — отдельный `StateFlow<SyncProgress?>` у `SyncEngine` (`core`), проброшенный через `DaemonService`, `/api/v1/status`, `MimirUiModel` в `StatusScreen` (макет A). `SyncStatus` остаётся грубым состоянием, к нему добавляется `Offline`.

**Tech Stack:** Kotlin 2.4.21 (KMP), JDK 27, Ktor (client + server CIO), Compose Multiplatform 1.12, kotlinx.serialization, gmazzo buildconfig 6.0.0.

**Spec:** `docs/superpowers/specs/2026-10-24-mimir-desktop-polish-design.md` (главнее плана; родительская — `2026-10-23-mimir-design.md`).

## Global Constraints

- Машина — Arch Linux. На Windows ничего не запускать; MSI проверяется только в CI.
- Строка статуса до подключения — ровно `Mimir: not connected`; существующие метки `label()` не меняются.
- `ui` не зависит от `daemon`; зависимости только вниз (точки входа → `ui` → `drive` → `core`).
- `mise run test` и `mise run build` проходят без секретов и без Android SDK; configuration cache и build cache не ломаются.
- Версии зависимостей — только `gradle/libs.versions.toml`; идентификаторы — только `gradle.properties`; пакеты `dev.mimir.<module>`.
- Существующие поля `/api/v1/status` не меняются, новые только добавляются.
- Вшитые OAuth-значения — только в BuildConfig модуля `daemon`, никогда в `core`.
- TDD: сначала падающий тест. Тесты: `commonTest` для `core`/`drive`, `test` для `daemon`/`app-desktop`, `desktopTest` для `ui`.
- Коммиты — conventional (`feat(core): …`), каждое сообщение заканчивается строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Прогресс и отклонения — в `.superpowers/sdd/2026-10-24-mimir-desktop-polish/progress.md` (`Ruling:`).
- Пользовательские тексты UI — по-русски, без технических терминов; технические детали только под «Подробнее».

## Review Focus

- **Сеть пропала между тиками** (`SyncScheduler.tick` падает на `getChanges`): пользователь ожидает «Нет подключения к интернету», а не вечное «up to date». → тест в Task 10.
- **Пользователь закрыл вкладку браузера / нажал «Отмена» в окне Google** (`error=access_denied` или тайм-аут): мастер показывает понятную причину и кнопку повтора, порт приёмника освобождён. → тесты в Task 5.
- **Повторное нажатие «Войти через Google»** до завершения первой попытки: старый приёмник закрыт, работает только новый `state`. → тест в Task 5.
- **Старый `tokens.json` без `clientKind`** после обновления: синхронизация продолжает работать без повторного входа. → тест в Task 2.
- **Ошибка на файле в фазе `Comparing`** (хэш спорного файла не читается): не роняет весь прогон, файл попадает в проблемы. → тест в Task 8.

---

## File Structure

| Файл | Ответственность |
|---|---|
| `drive/.../auth/GoogleClient.kt` (new) | `GoogleClientKind`, `GoogleClientCredentials`, `GoogleAuthClients` |
| `drive/.../auth/BrowserLogin.kt` (new) | PKCE, `state`, URL, разбор callback — чистые функции |
| `drive/.../auth/OAuthTokens.kt`, `GoogleAuthClient.kt`, `client/DriveHttpClient.kt` | `clientKind`, выбор клиента при refresh, перевод ошибок в core-исключения |
| `core/.../SyncProgress.kt` (new) | модель прогресса, `etaSeconds` |
| `core/.../RemoteExceptions.kt` (new) | `RemoteUnavailableException`, `RemoteAuthException` |
| `core/.../sync/SyncEngine.kt`, `DefaultSyncEngine.kt`, `StubSyncEngine.kt` | `Offline`, `progress`, пофайловая устойчивость |
| `daemon/.../GoogleClients.kt` (new) | разрешение пар клиентов (env → config → вшитое) |
| `daemon/.../BrowserLoginReceiver.kt` (new) | одноразовый loopback-приёмник |
| `daemon/.../ProgressDto.kt` (new) | сериализуемый `SyncProgressDto` + маппинг в обе стороны (используют `Routes` и `HttpDaemonClient`) |
| `daemon/.../DaemonService.kt`, `DaemonRuntime.kt`, `DaemonConfig.kt`, `Routes.kt`, `SyncScheduler.kt` | новые методы и поля |
| `app-desktop/.../HttpDaemonClient.kt`, `DaemonServiceUiModel.kt`, `Main.kt`, `tray/TrayLabel.kt` (new) | клиент API, модель UI, окно, подпись трея |
| `ui/.../model/*`, `screens/SetupWizardScreen.kt`, `screens/StatusScreen.kt`, `screens/SyncTexts.kt` (new), `MimirApp.kt` | интерфейс |
| `packaging/arch/mimir.sh`, `app-desktop/build.gradle.kts`, `.github/workflows/release.yml`, `.github/scripts/build-arch.sh` | запуск и релиз |
| `README.md`, `AGENTS.md`, `.changeset/desktop-polish.md` | документация |

Пути ниже сокращены: `drive/...` = `drive/src/commonMain/kotlin/dev/mimir/drive`, `core/...` = `core/src/commonMain/kotlin/dev/mimir/core`, `daemon/...` = `daemon/src/main/kotlin/dev/mimir/daemon`, `desktop/...` = `app-desktop/src/main/kotlin/dev/mimir/desktop`, `ui/...` = `ui/src/commonMain/kotlin/dev/mimir/ui`.

---

### Task 1: Окно под niri (диагностика и исправление)

**Files:**
- Modify: `desktop/Main.kt`, `ui/MimirApp.kt`, `app-desktop/build.gradle.kts`, `packaging/arch/mimir.sh`
- Record: `.superpowers/sdd/2026-10-24-mimir-desktop-polish/progress.md`

**Interfaces:** Produces: ничего для других задач.

- [ ] **Step 1: Диагностика без правок.** В отдельном терминале: `MIMIR_CONFIG_DIR=$(mktemp -d) mise run desktop`, затем `niri msg windows` — записать, X11/XWayland ли окно и его размер. Повторить с `_JAVA_AWT_WM_NONREPARENTING=1 MIMIR_CONFIG_DIR=$(mktemp -d) mise run desktop`: тайловый режим, плавающий (`niri msg action toggle-window-floating`), ручной ресайз. Если чёрная область осталась — `SKIKO_RENDER_API=SOFTWARE`, затем `JAVA_TOOL_OPTIONS=-Dawt.toolkit.name=WLToolkit`. Записать `Ruling:` с найденной рабочей комбинацией. Шаги ниже предполагают `_JAVA_AWT_WM_NONREPARENTING=1`; если сработало другое — подставить его в Steps 2–3.
- [ ] **Step 2: Переменная во всех Linux-точках запуска.** `app-desktop/build.gradle.kts`: `tasks.named<JavaExec>("run") { environment("_JAVA_AWT_WM_NONREPARENTING", "1") }`. `packaging/arch/mimir.sh`: `export _JAVA_AWT_WM_NONREPARENTING=1` перед `exec java …` (`.desktop` вызывает `mimir`, отдельной правки не нужно). MSI не трогать.
- [ ] **Step 3: Размер окна.** `Main.kt`: `Window(state = rememberWindowState(size = DpSize(960.dp, 680.dp)), …)`, внутри `LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(720, 520) }`. `MimirApp.kt`: корневой `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter)` с содержимым `Modifier.widthIn(max = 880.dp).fillMaxWidth()`.
- [ ] **Step 4: Проверка.** `mise run test` — PASS. Ручная приёмка спеки §3.4 (оба режима, ресайз, минимальный размер, трей на noctalia/waybar: иконка, три пункта меню, обновление подписи). Результат — в `progress.md`; найденные дефекты трея — отдельным коммитом `fix(desktop): …` в этой задаче.
- [ ] **Step 5: Commit** `fix(desktop): render correctly under non-reparenting Wayland compositors (niri)`.

---

### Task 2: Типы клиентов Google и токены

**Files:**
- Create: `drive/.../auth/GoogleClient.kt`
- Modify: `drive/.../auth/OAuthTokens.kt`, `drive/.../auth/GoogleAuthClient.kt`, `drive/.../client/DriveHttpClient.kt`, `daemon/...DaemonRuntime.kt` (только конструирование), тесты, где создаются `GoogleAuthClient`/`DriveHttpClient`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/auth/GoogleAuthClientTest.kt`, `GoogleAuthClientsTest.kt` (new)

**Interfaces:**
- Produces:
  ```kotlin
  enum class GoogleClientKind { Desktop, Device }
  data class GoogleClientCredentials(val kind: GoogleClientKind, val clientId: String, val clientSecret: String?)
  class GoogleAuthClients(val desktop: GoogleAuthClient?, val device: GoogleAuthClient?) {
      fun forKind(kind: GoogleClientKind): GoogleAuthClient?
  }
  // GoogleAuthClient(httpClient, credentials: GoogleClientCredentials, clock = Clock.System)
  //   val kind: GoogleClientKind — все выдаваемые им OAuthTokens помечены этим kind
  // OAuthTokens(..., val clientKind: GoogleClientKind = GoogleClientKind.Device)
  // DriveHttpClient(httpClient, tokenStorage, authClients: GoogleAuthClients)
  ```

- [ ] **Step 1: Failing tests.**
  ```kotlin
  @Test fun authorizationUrlNeverContainsClientSecret() {
      val url = client(secret = "s3cr3t").buildAuthorizationUrl("http://127.0.0.1:5000/callback", "chal", "st")
      assertFalse("client_secret" in url); assertFalse("s3cr3t" in url)
  }
  @Test fun tokensAreTaggedWithIssuingClientKind() = runTest { /* MockEngine token endpoint → exchangeAuthorizationCode */
      assertEquals(GoogleClientKind.Desktop, desktopClient.exchangeAuthorizationCode("c", "v", "r").clientKind)
  }
  @Test fun legacyTokenJsonWithoutClientKindDecodesAsDevice() {
      val t = Json.decodeFromString<OAuthTokens>("""{"accessToken":"a","refreshToken":"r","expiresAtEpochSeconds":1}""")
      assertEquals(GoogleClientKind.Device, t.clientKind)
  }
  @Test fun refreshUsesClientMatchingTokenKind() = runTest {
      // expired Desktop-токен; MockEngine запоминает client_id из формы refresh
      assertEquals("desktop-id", capturedClientId)
  }
  @Test fun refreshWithoutMatchingClientFailsWithAuthError() = runTest {
      // GoogleAuthClients(desktop = null, device = …), expired Desktop-токен
      assertFailsWith<DriveAuthException> { driveClient.getValidToken() }  // в Task 9 станет RemoteAuthException
  }
  ```
- [ ] **Step 2: Run** `./gradlew :drive:jvmTest` — FAIL (нет типов).
- [ ] **Step 3: Implement.** Создать `GoogleClient.kt`; `GoogleAuthClient` принимает `GoogleClientCredentials`, `buildAuthorizationUrl` без `secretPart`, `toOAuthTokens` ставит `clientKind = credentials.kind`, refresh сохраняет kind; `DriveHttpClient` берёт клиента через `authClients.forKind(tokens.clientKind)`, при `null` — `DriveAuthException(Unauthorized, "Sign in again: no Google client for ${kind}")`. `DaemonRuntime` пока строит `GoogleAuthClients(desktop = null, device = GoogleAuthClient(httpClient, GoogleClientCredentials(Device, id, secret)))` из старых полей (полный конфиг — Task 4).
- [ ] **Step 4: Run** `./gradlew :drive:jvmTest :daemon:test` — PASS.
- [ ] **Step 5: Commit** `feat(drive): tag OAuth tokens with issuing client kind and keep client secret out of auth URL`.

---

### Task 3: Логика входа через браузер (PKCE)

**Files:**
- Create: `drive/.../auth/BrowserLogin.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/auth/BrowserLoginTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object Pkce {
      fun newVerifier(): String                 // 43 символа base64url без '=': два Uuid.random() (CSPRNG stdlib) → 32 байта
      fun challenge(verifier: String): String   // base64url(SHA-256(ascii(verifier))) без '=', через core Sha256
  }
  data class BrowserLoginRequest(val authUrl: String, val redirectUri: String, val state: String, val verifier: String)
  fun GoogleAuthClient.newBrowserLogin(port: Int): BrowserLoginRequest  // redirectUri = "http://127.0.0.1:$port/callback"
  sealed interface LoginCallback {
      data class Code(val code: String) : LoginCallback
      data object Denied : LoginCallback
      data class Invalid(val reason: String) : LoginCallback
  }
  fun parseLoginCallback(query: Map<String, String>, expectedState: String): LoginCallback
  ```

- [ ] **Step 1: Failing tests.**
  ```kotlin
  @Test fun challengeMatchesRfc7636Vector() =
      assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
  @Test fun verifierIsUrlSafeAndLongEnough() { val v = Pkce.newVerifier(); assertTrue(v.length in 43..128); assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" }) }
  @Test fun verifiersDiffer() = assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
  @Test fun authUrlUsesLoopbackRedirectAndS256() { val r = client.newBrowserLogin(5123)
      assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A5123%2Fcallback" in r.authUrl || "redirect_uri=http://127.0.0.1:5123/callback" in r.authUrl)
      assertTrue("code_challenge_method=S256" in r.authUrl); assertTrue("state=${r.state}" in r.authUrl) }
  @Test fun callbackWithCodeAndMatchingState() = assertEquals(LoginCallback.Code("abc"), parseLoginCallback(mapOf("code" to "abc", "state" to "s"), "s"))
  @Test fun callbackAccessDenied() = assertEquals(LoginCallback.Denied, parseLoginCallback(mapOf("error" to "access_denied", "state" to "s"), "s"))
  @Test fun callbackWrongStateIsInvalid() = assertIs<LoginCallback.Invalid>(parseLoginCallback(mapOf("code" to "abc", "state" to "x"), "s"))
  @Test fun callbackWithoutCodeIsInvalid() = assertIs<LoginCallback.Invalid>(parseLoginCallback(mapOf("state" to "s"), "s"))
  ```
- [ ] **Step 2: Run** `./gradlew :drive:jvmTest --tests '*BrowserLoginTest*'` — FAIL.
- [ ] **Step 3: Implement** в `BrowserLogin.kt`; base64url — `kotlin.io.encoding.Base64.UrlSafe` с `PaddingOption.ABSENT`; `state` — `Uuid.random().toHexString()`; несовпадение `state` проверяется раньше `error`.
- [ ] **Step 4: Run** `./gradlew :drive:allTests` — PASS.
- [ ] **Step 5: Commit** `feat(drive): add PKCE browser login request and callback parsing`.

---

### Task 4: Конфигурация клиентов и вшитые значения

**Files:**
- Create: `daemon/...GoogleClients.kt`
- Modify: `daemon/build.gradle.kts`, `daemon/...DaemonConfig.kt`, `daemon/...DaemonRuntime.kt`, `daemon/...DaemonService.kt`, `daemon/src/test/.../FakeDaemonService.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/DaemonConfigTest.kt` (существующий или new), `GoogleClientsTest.kt` (new)

**Interfaces:**
- Consumes: Task 2 `GoogleClientCredentials`, `GoogleAuthClients`.
- Produces:
  ```kotlin
  // DaemonConfig: googleDesktopClientId, googleDesktopClientSecret, googleDeviceClientId, googleDeviceClientSecret: String? = null
  //   (поля googleClientId/googleClientSecret удаляются; в config.json старые ключи читаются как Device — через @JsonNames)
  object EmbeddedGoogleClients { val desktop: GoogleClientCredentials?; val device: GoogleClientCredentials? } // из BuildConfig
  fun DaemonConfig.googleCredentials(embedded: EmbeddedGoogleClients = EmbeddedGoogleClients): Pair<GoogleClientCredentials?, GoogleClientCredentials?> // (desktop, device)
  enum class LoginMethod { Browser, DeviceCode }
  // DaemonService: suspend fun loginMethods(): Set<LoginMethod>
  ```

- [ ] **Step 1: Failing tests.**
  ```kotlin
  @Test fun newEnvVariablesFillBothClients() // MIMIR_GOOGLE_DESKTOP_CLIENT_ID/SECRET и MIMIR_GOOGLE_DEVICE_CLIENT_ID/SECRET → четыре поля
  @Test fun legacyEnvVariablesAreDeviceAlias() // MIMIR_GOOGLE_CLIENT_ID=old → googleDeviceClientId == "old"; новое имя имеет приоритет над старым
  @Test fun legacyConfigJsonKeysAreDeviceAlias() // {"googleClientId":"old"} → googleDeviceClientId == "old"
  @Test fun configuredValuesOverrideEmbedded() // embedded desktop "emb", config desktop "cfg" → desktop.clientId == "cfg"
  @Test fun emptyStringMeansAbsent() // embedded desktop id "" и нет конфига → desktop == null
  @Test fun toStringRedactsBothSecrets() // нет "s3cr3t" в toString()
  @Test fun loginMethodsReflectAvailableClients() // только device → setOf(DeviceCode); оба → setOf(Browser, DeviceCode)
  ```
- [ ] **Step 2: Run** `./gradlew :daemon:test` — FAIL.
- [ ] **Step 3: Implement.**
  - `daemon/build.gradle.kts`: плагин `alias(libs.plugins.buildconfig)`; `buildConfig { packageName("dev.mimir.daemon"); className("DaemonBuildConfig"); listOf("DESKTOP_CLIENT_ID","DESKTOP_CLIENT_SECRET","DEVICE_CLIENT_ID","DEVICE_CLIENT_SECRET").forEach { buildConfigField("GOOGLE_$it", providers.environmentVariable("MIMIR_BUILD_GOOGLE_$it").orElse("")) } }`.
  - `applyEnv`: новые переменные; старые `MIMIR_GOOGLE_CLIENT_ID/SECRET` → Device-поля, если новые не заданы, с `logger.warning("MIMIR_GOOGLE_CLIENT_ID is deprecated, use MIMIR_GOOGLE_DEVICE_CLIENT_ID")`.
  - `DaemonRuntime`: `GoogleAuthClients` из `googleCredentials()`; `startDeviceLogin`/`pollDeviceLogin` используют `authClients.device` (сообщение `SyncNotConfiguredException("Google device client is not configured")`); `googleDriveFactory` возвращает `null`, только если нет ни одного клиента.
  - `FakeDaemonService`: `var methods: Set<LoginMethod> = emptySet()`, `override suspend fun loginMethods() = methods`.
- [ ] **Step 4: Run** `./gradlew :daemon:test :app-desktop:test` — PASS; `mise run build` без переменных — PASS.
- [ ] **Step 5: Commit** `feat(daemon): configure desktop and device Google clients with embedded release defaults`.

---

### Task 5: Loopback-приёмник, `DaemonService` и HTTP API входа

**Files:**
- Create: `daemon/...BrowserLoginReceiver.kt`
- Modify: `daemon/...DaemonService.kt`, `daemon/...DaemonRuntime.kt`, `daemon/...Routes.kt`, `FakeDaemonService.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/BrowserLoginTest.kt` (new), `ConflictAndDeviceAuthRoutesTest.kt`

**Interfaces:**
- Consumes: Task 3 `newBrowserLogin`, `parseLoginCallback`; Task 2 `exchangeAuthorizationCode`; Task 4 `authClients.desktop`, `LoginMethod`.
- Produces:
  ```kotlin
  class BrowserLoginReceiver(timeout: Duration = 5.minutes) : AutoCloseable {
      val port: Int                                   // после start()
      fun start()                                     // embeddedServer(CIO, host = "127.0.0.1", port = 0)
      suspend fun awaitCallback(): Map<String, String>?   // null — тайм-аут; сервер останавливается после первого /callback
  }
  sealed interface BrowserLoginPoll {
      data object Pending : BrowserLoginPoll
      data class Authorized(val email: String?) : BrowserLoginPoll
      data class Failed(val reason: String) : BrowserLoginPoll
  }
  // DaemonService: suspend fun startBrowserLogin(): String; suspend fun pollBrowserLogin(): BrowserLoginPoll
  // HTTP: GET /api/v1/auth/methods → {"methods":["browser","device_code"]}
  //       POST /api/v1/auth/browser/start → {"authUrl":"…"} | 409 {"error":"Browser sign-in is not available"}
  //       GET /api/v1/auth/browser/poll → {"status":"pending"} | {"status":"authorized","email":…} | {"status":"failed","error":"…"}
  ```

- [ ] **Step 1: Failing tests** (`BrowserLoginTest`, настоящий приёмник, `MockEngine` для `oauth2.googleapis.com/token` и `userinfo`):
  ```kotlin
  @Test fun successfulCallbackStoresDesktopTokens() // start → GET http://127.0.0.1:$port/callback?code=c&state=<из authUrl> → poll == Authorized("u@x"); tokenStorage.load()!!.clientKind == Desktop; ответ браузеру содержит "Готово"
  @Test fun deniedCallbackFailsWithHumanReason() // error=access_denied → Failed("Вход отменён"); ответ браузеру содержит "Вход отменён"
  @Test fun wrongStateIsRejected() // state=bad → poll остаётся Pending до корректного запроса; tokens не сохранены
  @Test fun timeoutFails() // receiver timeout = 200.ms → Failed("Время ожидания истекло"); порт закрыт (Socket connect бросает)
  @Test fun restartCancelsPreviousAttempt() // start дважды → первый порт закрыт; callback со state первой попытки не принимается
  @Test fun tokenExchangeNetworkErrorFails() // MockEngine бросает IOException → Failed("Не удалось связаться с Google")
  ```
  В `ConflictAndDeviceAuthRoutesTest`: `authMethodsListsAvailable`, `browserStartReturnsUrl`, `browserStartConflictWhenUnavailable` (409), `browserPollMapsAllStates`.
- [ ] **Step 2: Run** `./gradlew :daemon:test` — FAIL.
- [ ] **Step 3: Implement.** `DaemonRuntime` хранит текущую попытку (`receiver`, `request`, `Job`, `MutableStateFlow<BrowserLoginPoll>`) под `Mutex`; `startBrowserLogin` закрывает предыдущую, стартует приёмник, `newBrowserLogin(receiver.port)`, в `scope.launch` ждёт callback: `Code` → обмен и сохранение как в `pollDeviceLogin` (вынести общий `completeLogin(tokens, client)`), `Denied` → `Failed("Вход отменён")`, `Invalid` → игнор (ждать дальше), `null` → `Failed("Время ожидания истекло")`. Страница браузера — минимальный HTML (`<h1>Готово! Можно вернуться в Mimir</h1>` / `<h1>Вход отменён</h1><p>Вернитесь в Mimir и попробуйте снова.</p>`), `charset=utf-8`.
- [ ] **Step 4: Run** `./gradlew :daemon:test` — PASS.
- [ ] **Step 5: Commit** `feat(daemon): add loopback browser sign-in with PKCE to daemon service and API`.

---

### Task 6: Вход через браузер в десктопе и мастере

**Files:**
- Modify: `desktop/HttpDaemonClient.kt`, `desktop/DaemonServiceUiModel.kt`, `ui/.../model/MimirUiModel.kt`, `FakeMimirUiModel.kt`, `EngineMimirUiModel.kt`, `ui/.../screens/SetupWizardScreen.kt`, `.github/workflows/release.yml`, `.github/scripts/build-arch.sh` (если окружение не наследуется)
- Test: `app-desktop/src/test/.../HttpDaemonClientTest.kt`, `DaemonServiceUiModelTest.kt`, `ui/src/desktopTest/.../SetupWizardScreenTest.kt`

**Interfaces:**
- Consumes: Task 5 API и `BrowserLoginPoll`, Task 4 `LoginMethod`.
- Produces (в `ui`, без зависимости от `daemon`):
  ```kotlin
  enum class UiLoginMethod { Browser, DeviceCode }
  sealed interface UiBrowserPoll { data object Pending; data class Authorized(val email: String?); data class Failed(val reason: String) }
  // MimirUiModel: suspend fun loginMethods(): Set<UiLoginMethod>; suspend fun startBrowserLogin(): String; suspend fun pollBrowserLogin(): UiBrowserPoll
  ```

- [ ] **Step 1: Failing tests.**
  - `HttpDaemonClientTest`: `loginMethodsParsesList`, `startBrowserLoginReturnsUrl`, `pollBrowserLoginMapsFailed` (MockEngine).
  - `SetupWizardScreenTest`:
    ```kotlin
    @Test fun browserMethodShowsBigButtonAndCodeLink() // методы {Browser, DeviceCode} → есть "Войти через Google" и "Войти с помощью кода на другом устройстве"
    @Test fun onlyDeviceCodeShowsCodeScreenDirectly() // {DeviceCode} → виден userCode, кнопки "Войти через Google" нет
    @Test fun noMethodsShowsNotConfiguredMessage() // {} → "Вход в Google не настроен в этой сборке"
    @Test fun browserLoginWaitsThenAdvances() // клик → openUri(authUrl) вызван, "Ждём подтверждения в браузере…", poll Authorized → шаг пароля
    @Test fun browserLoginFailureShowsReasonAndRetry() // Failed("Вход отменён") → текст и "Повторить"
    @Test fun cancelReturnsToButton() // клик "Отмена" → снова кнопка "Войти через Google"
    ```
- [ ] **Step 2: Run** `./gradlew :ui:desktopTest :app-desktop:test` — FAIL.
- [ ] **Step 3: Implement.** `GoogleStep` разделить на `BrowserLoginStep` и существующий `DeviceCodeStep`; выбор по `loginMethods()`. Открытие URL — `LocalUriHandler.openUri`; при исключении показать ссылку текстом с кнопкой «Скопировать ссылку». `DaemonServiceUiModel` маппит `LoginMethod`/`BrowserLoginPoll` в UI-типы; `EngineMimirUiModel` — `unsupported()`, `loginMethods()` → `emptySet()`. `release.yml`: в шаг `./gradlew ${{ matrix.task }}` и в Arch-джоб добавить `env:` с `MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_ID/SECRET`, `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_ID/SECRET: ${{ secrets.… }}`. Docker-образ не получает вшитых значений — `Ruling:` в progress.md (демон в Docker настраивается переменными).
- [ ] **Step 4: Run** `mise run test` — PASS.
- [ ] **Step 5: Commit** `feat(desktop): sign in with Google via browser in setup wizard`.

---

### Task 7: Модель прогресса и `Offline` (`core`)

**Files:**
- Create: `core/.../SyncProgress.kt`, `core/.../RemoteExceptions.kt`
- Modify: `core/.../sync/SyncEngine.kt` (`Offline`, метка, `progress`), `core/.../sync/StubSyncEngine.kt`, все исчерпывающие `when (SyncStatus)`: `daemon/...Routes.kt` (`code()` → `"offline"`), `desktop/HttpDaemonClient.kt` (`"offline"` → `Offline`)
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SyncProgressTest.kt` (new)

**Interfaces:**
- Produces:
  ```kotlin
  // SyncStatus: data object Offline; label() → "Mimir: waiting for network"
  // SyncEngine: val progress: StateFlow<SyncProgress?>
  enum class SyncPhase { Scanning, Comparing, Applying, Finished }
  enum class SyncItemAction { Upload, Download, DeleteLocal, DeleteRemote, Conflict }
  enum class SyncProblemKind { ReadFailed, WriteFailed, DecryptFailed, Unknown }
  data class SyncItem(val path: String, val action: SyncItemAction)
  data class CompletedItem(val path: String, val action: SyncItemAction, val at: Instant)
  data class SyncProblem(val path: String, val action: SyncItemAction, val kind: SyncProblemKind, val detail: String)
  data class SyncProgress(
      val phase: SyncPhase, val startedAt: Instant, val finishedAt: Instant? = null,
      val applyingStartedAt: Instant? = null, val scanned: Int = 0,
      val total: Int = 0, val done: Int = 0, val bytesTotal: Long = 0, val bytesDone: Long = 0,
      val current: SyncItem? = null, val upcoming: List<SyncItem> = emptyList(),
      val completed: List<CompletedItem> = emptyList(), val problems: List<SyncProblem> = emptyList(),
  ) {
      fun etaSeconds(now: Instant): Long?
      companion object { const val UPCOMING_LIMIT = 5; const val COMPLETED_LIMIT = 200; const val DELETE_WEIGHT_BYTES = 4096L }
  }
  class RemoteUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
  class RemoteAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
  ```

- [ ] **Step 1: Failing tests.**
  ```kotlin
  @Test fun offlineLabel() = assertEquals("Mimir: waiting for network", SyncStatus.Offline.label())
  @Test fun notConfiguredLabelUnchanged() = assertEquals("Mimir: not connected", SyncStatus.NotConfigured.label())
  @Test fun etaNullBeforeTwoSeconds() // applyingStartedAt = t0, bytesDone = 100, now = t0 + 1.s → null
  @Test fun etaNullWhenNothingDone() // now = t0 + 10.s, bytesDone = 0 → null
  @Test fun etaProportionalToRemainingBytes() // t0 + 10.s, bytesDone 1000, bytesTotal 4000 → 30
  @Test fun etaNullOutsideApplying() // phase = Scanning → null
  ```
- [ ] **Step 2: Run** `./gradlew :core:jvmTest` — FAIL.
- [ ] **Step 3: Implement.** `DefaultSyncEngine`/`StubSyncEngine` пока отдают `MutableStateFlow(null)`; добавить ветки `Offline` в `Routes.code()` и разбор `"offline"` в `HttpDaemonClient`.
- [ ] **Step 4: Run** `mise run test` — PASS.
- [ ] **Step 5: Commit** `feat(core): add sync progress model, offline status and remote error types`.

---

### Task 8: Движок публикует прогресс и переживает ошибки файлов

**Files:**
- Modify: `core/.../sync/DefaultSyncEngine.kt`, `core/.../sync/SyncPlanBuilder.kt`, `core/.../testing/InMemoryLocalVault.kt`, `core/.../testing/InMemoryRemoteStorage.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/DefaultSyncEngineTest.kt`, `SyncEngineProgressTest.kt` (new)

**Interfaces:**
- Consumes: Task 7 модель и исключения.
- Produces: `DefaultSyncEngine.progress`; тестовые хуки `InMemoryLocalVault.failReadFor: MutableSet<String>`, `InMemoryRemoteStorage.failListWith: Throwable?`.

- [ ] **Step 1: Failing tests** (заменить `syncNow_setsErrorStatusOnFailure`: глобальный `throwOnRead` теперь даёт проблему, а не `Error`):
  ```kotlin
  @Test fun progressCountsAllPlannedActions() // 3 локальных файла → Finished, total 3, done 3, completed.size 3, problems пусты, bytesTotal == сумме размеров
  @Test fun upcomingIsCappedAtFive() // 8 файлов; снимок при первом current (через collect в фоне) → upcoming.size 5
  @Test fun completedIsCappedAt200() // 205 файлов → completed.size 200, done 205
  @Test fun failingFileBecomesProblemAndOthersSync() // failReadFor = {"b.md"}, файлы a,b,c → Idle; problems == [SyncProblem("b.md", Upload, ReadFailed, …)]; a и c в remote; запись b нет в индексе
  @Test fun failedFileIsRetriedNextRun() // убрать b из failReadFor, второй syncNow → b в remote, problems пусты
  @Test fun comparingFailureForOneFileIsProblem() // конфликтующий путь с изменёнными обеими сторонами, чтение локального падает при хэшировании → problems содержит путь, остальные синхронизированы
  @Test fun remoteUnavailableSetsOffline() // failListWith = RemoteUnavailableException("no net") → assertFailsWith; status == Offline
  @Test fun remoteAuthFailureSetsError() // failListWith = RemoteAuthException("revoked") → status is Error
  @Test fun cryptFailureIsDecryptProblem() // remote read бросает CryptException для одного файла → kind == DecryptFailed
  ```
- [ ] **Step 2: Run** `./gradlew :core:jvmTest` — FAIL.
- [ ] **Step 3: Implement.**
  - Фазы: `Scanning` до списков (`scanned` = локальные + удалённые файлы), `Comparing` на время `buildPlan`, `Applying`, `Finished` с `finishedAt` (публикуется и при фатальной ошибке).
  - `SyncPlanBuilder.buildPlan` получает необязательный параметр `onReconcileFailure: ((path: String, error: Throwable) -> Unit)? = null`. В цикле по путям вызов `reconcile(...)` оборачивается в `try`: `RemoteUnavailableException`, `RemoteAuthException`, `CancellationException` пробрасываются; иное исключение при заданном колбэке → действие `NoOp(path)` и вызов колбэка, без колбэка — проброс (старое поведение для существующих тестов `SyncPlanBuilderTest`). Движок в колбэке добавляет `SyncProblem(path, Upload, kind, detail)` (kind — по тем же правилам, что ниже; действие `Upload` как наиболее вероятное для локального изменения).
  - В `Applying` считаются только `Upload/Download/DeleteLocal/DeleteRemote/Conflict`; `UpdateMetadata`/`NoOp` выполняются молча. Вес: Upload — `localEntry.size`, Download — `remoteEntry.size`, Conflict — сумма, удаления — `DELETE_WEIGHT_BYTES`.
  - Каждое действие в `try`: фатальные три исключения пробрасываются; `CryptException` → `DecryptFailed`; исключение из `localVault.read` → `ReadFailed`; из `localVault.write/delete` → `WriteFailed` (обернуть эти вызовы в приватные `readLocal`/`writeLocal`/`deleteLocal`, бросающие `private class LocalIoFailure(kind, cause)`); прочее → `Unknown`; `detail = e.message ?: e::class.simpleName`.
  - `syncNow`: `RemoteUnavailableException` → `Offline`, иначе `Error`; исключение пробрасывается (поведение `SyncScheduler` сохраняется).
- [ ] **Step 4: Run** `./gradlew :core:allTests` — PASS (включая `SyncEngineIntegrationTest`).
- [ ] **Step 5: Commit** `feat(core): publish sync progress and continue past per-file failures`.

---

### Task 9: Перевод ошибок Drive в core-исключения

**Files:**
- Modify: `drive/.../client/DriveHttpClient.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/client/DriveHttpClientTest.kt` (существующий или new), `GoogleAuthClientsTest.kt` (из Task 2)

**Interfaces:**
- Consumes: Task 7 `RemoteUnavailableException`, `RemoteAuthException`.
- Produces: `DriveHttpClient.executeWithRetry`, `getValidToken`, `forceRefreshToken` бросают только core-исключения для сети/доступа.

- [ ] **Step 1: Failing tests.**
  ```kotlin
  @Test fun ioExceptionBecomesRemoteUnavailable() // MockEngine бросает kotlinx.io.IOException → assertFailsWith<RemoteUnavailableException>
  @Test fun timeoutBecomesRemoteUnavailable() // HttpRequestTimeoutException → RemoteUnavailableException
  @Test fun persistent503BecomesRemoteUnavailable() // 503 на все попытки → RemoteUnavailableException
  @Test fun revokedRefreshBecomesRemoteAuth() // token endpoint invalid_grant → RemoteAuthException
  @Test fun missingTokensBecomeRemoteAuth() // пустой TokenStorage → RemoteAuthException
  ```
  Обновить `refreshWithoutMatchingClientFailsWithAuthError` из Task 2 на `RemoteAuthException`.
- [ ] **Step 2: Run** `./gradlew :drive:jvmTest` — FAIL.
- [ ] **Step 3: Implement.** В `executeWithRetry`: `block(token)` в `try`, `IOException`/`HttpRequestTimeoutException` → `RemoteUnavailableException("Google Drive is unreachable: …", e)`; после исчерпания ретраев на 503/5xx → `RemoteUnavailableException`. В `getValidToken`/`forceRefreshToken`: `DriveAuthException` (включая `AuthRevokedException` и отсутствие токенов) → `RemoteAuthException`. `GoogleAuthClient` и device-маршруты не трогать (там `DriveHttpException` ловит `Routes`).
- [ ] **Step 4: Run** `./gradlew :drive:allTests :daemon:test` — PASS.
- [ ] **Step 5: Commit** `feat(drive): map network and auth failures to core remote exceptions`.

---

### Task 10: Прогресс в демоне и HTTP API

**Files:**
- Create: `daemon/...ProgressDto.kt`
- Modify: `daemon/...DaemonService.kt`, `daemon/...DaemonRuntime.kt`, `daemon/...Routes.kt`, `daemon/...SyncScheduler.kt`, `FakeDaemonService.kt`
- Test: `daemon/src/test/.../RoutesTest.kt`, `SyncSchedulerTest.kt`, `DaemonRuntimeTest.kt`

**Interfaces:**
- Consumes: Task 7/8 `SyncProgress`, `progress`.
- Produces:
  ```kotlin
  // DaemonService: val progress: StateFlow<SyncProgress?>
  @Serializable data class SyncProgressDto(
      val phase: String, val startedAt: String, val finishedAt: String?, val applyingStartedAt: String?,
      val scanned: Int, val total: Int, val done: Int, val bytesTotal: Long, val bytesDone: Long,
      val etaSeconds: Long?, val current: SyncItemDto?, val upcoming: List<SyncItemDto>,
      val completed: List<CompletedItemDto>, val problems: List<SyncProblemDto>,
  )  // phase/action/kind — snake_case строки ("applying", "delete_local", "read_failed"); время ISO-8601
  fun SyncProgress.toDto(now: Instant): SyncProgressDto
  fun SyncProgressDto.toModel(): SyncProgress
  // StatusResponse: + val progress: SyncProgressDto? = null
  ```

- [ ] **Step 1: Failing tests.**
  ```kotlin
  @Test fun statusIncludesProgress() // FakeDaemonService.progress = Applying 12/87 → json["progress"]["done"] == 12, ["phase"] == "applying", ["total"] == 87
  @Test fun statusOmitsNothingExisting() // ключи status,label,vaultPath,lastSyncAt,isLocked,activeConflicts,accountEmail на месте
  @Test fun statusOfflineCode() // status = Offline → "offline", label "Mimir: waiting for network"
  @Test fun progressDtoRoundTrips() // toDto(now).toModel() == исходный
  @Test fun tickNetworkFailureSchedulesFullSync() // SyncSchedulerTest: getChanges бросает RemoteUnavailableException → на следующем тике вызывается syncEngine.syncNow()
  @Test fun runtimeExposesEngineProgress() // DaemonRuntimeTest: после синхронизации service.progress.value?.phase == Finished
  ```
- [ ] **Step 2: Run** `./gradlew :daemon:test` — FAIL.
- [ ] **Step 3: Implement.** `DaemonRuntime`: `_progress` проксирует `engine.progress` в существующем `statusJob`-подобном сборщике, при `teardown` значение сохраняется (сводка последнего прогона). `SyncScheduler.tick`: в `catch` ставить `retryOnNextTick.set(true)`. `FakeDaemonService.progress = MutableStateFlow<SyncProgress?>(null)`.
- [ ] **Step 4: Run** `./gradlew :daemon:test` — PASS.
- [ ] **Step 5: Commit** `feat(daemon): expose sync progress and offline status over API`.

---

### Task 11: Прогресс в `app-desktop` (HTTP-клиент, модель, трей)

**Files:**
- Create: `desktop/tray/TrayLabel.kt`
- Modify: `desktop/HttpDaemonClient.kt`, `desktop/DaemonServiceUiModel.kt`, `desktop/Main.kt`, `ui/.../model/MimirUiModel.kt`, `FakeMimirUiModel.kt`, `EngineMimirUiModel.kt`
- Test: `app-desktop/src/test/.../HttpDaemonClientTest.kt`, `DaemonServiceUiModelTest.kt`, `TrayLabelTest.kt` (new)

**Interfaces:**
- Consumes: Task 10 `SyncProgressDto.toModel()`, `DaemonService.progress`.
- Produces: `fun trayLabel(status: SyncStatus, progress: SyncProgress?): String`; `MimirUiModel.progress: StateFlow<SyncProgress?>` с реализациями в `DaemonServiceUiModel`, `FakeMimirUiModel(progress: MutableStateFlow<SyncProgress?> = MutableStateFlow(null), …)`, `EngineMimirUiModel` (`engine.progress`).

- [ ] **Step 1: Failing tests.**
  ```kotlin
  @Test fun trayShowsCountersWhileApplying() = assertEquals("Mimir: syncing 12/87", trayLabel(SyncStatus.Syncing, applying(12, 87)))
  @Test fun trayShowsPlainSyncingBeforePlan() = assertEquals("Mimir: syncing", trayLabel(SyncStatus.Syncing, scanning()))
  @Test fun trayShowsProblemsAfterRun() = assertEquals("Mimir: up to date (2 problems)", trayLabel(SyncStatus.Idle, finished(problems = 2)))
  @Test fun trayProblemSingular() = assertEquals("Mimir: up to date (1 problem)", trayLabel(SyncStatus.Idle, finished(problems = 1)))
  @Test fun trayNotConnectedUnchanged() = assertEquals("Mimir: not connected", trayLabel(SyncStatus.NotConfigured, null))
  @Test fun httpClientParsesProgress() // MockEngine /status с progress → client.progress.value?.done == 12
  ```
- [ ] **Step 2: Run** `./gradlew :app-desktop:test` — FAIL.
- [ ] **Step 3: Implement.** `HttpDaemonClient`: `StatusDto.progress: SyncProgressDto? = null`, `_progress` обновляется при каждом опросе (интервал уже 1 с). `Main.kt`: `combine(ready.status, ready.progress) { s, p -> trayLabel(s, p) }.distinctUntilChanged().collect { tray.update(it) }`. `DaemonServiceUiModel.progress = service.progress`; в `MimirUiModel` добавить `progress`, в `FakeMimirUiModel`/`EngineMimirUiModel` — реализации.
- [ ] **Step 4: Run** `./gradlew :app-desktop:test` — PASS.
- [ ] **Step 5: Commit** `feat(desktop): show sync progress in tray and HTTP client`.

---

### Task 12: Экран прогресса (макет A)

**Files:**
- Create: `ui/.../screens/SyncTexts.kt`
- Modify: `ui/.../screens/StatusScreen.kt`
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt`

**Interfaces:**
- Consumes: Task 7 `SyncProgress`, `etaSeconds`; Task 11 `MimirUiModel.progress`, `FakeMimirUiModel(progress = …)`.
- Produces: `object SyncTexts` с функциями `phaseTitle(status, progress)`, `actionNow(item)`, `problemHint(kind)`, `duration(seconds)` (`"1 мин 20 с"`, `"≈ 3 мин"`).

Тексты (точная копия, используется в тестах):
- фазы: `Ищу изменения…`, `Синхронизация…`, `✓ Всё синхронизировано`, `Нет подключения к интернету`, `Нужно снова войти в Google`;
- текущее действие: `⬆ Отправляю в Google Drive: <path>`, `⬇ Загружаю: <path>`, `Удаляю: <path>`, `Сохраняю обе версии: <path>`;
- проблемы: заголовок `⚠ <N> файл(а/ов) не удалось синхронизировать`, подпись `Mimir попробует ещё раз при следующей синхронизации`; `ReadFailed` → `не удалось прочитать файл — возможно, он открыт в другой программе`; `WriteFailed` → `не удалось сохранить файл — проверьте свободное место и права на папку`; `DecryptFailed` → `не удалось расшифровать — возможно, файл зашифрован другим паролем`; `Unknown` → `непредвиденная ошибка`;
- офлайн: `Mimir продолжит, когда сеть появится. Изменения на этом компьютере сохранены.`

- [ ] **Step 1: Failing tests** (`StatusScreenTest`, через `FakeMimirUiModel`):
  ```kotlin
  @Test fun notConnectedStillShowsExactLabel() // NotConfigured → onNodeWithText("Mimir: not connected") существует
  @Test fun scanningShowsCheckedCount() // Scanning, scanned 340 → "Ищу изменения…", "Проверено 340 файлов"
  @Test fun applyingShowsCountersEtaCurrentAndUpcoming() // 12/87, eta 180 → "12 из 87 файлов", "осталось ≈ 3 мин", "⬆ Отправляю в Google Drive: daily/2026-10-24.md", "и ещё 70"
  @Test fun problemsShownAboveWithHumanHint() // 1 ReadFailed → "⚠ 1 файл не удалось синхронизировать", подсказка ReadFailed; detail скрыт до клика "Подробнее"
  @Test fun completedListCollapsedByDefault() // completed 12 → "Готово (12)"; путь из completed не виден до клика
  @Test fun finishedShowsSummaryAndSyncButton() // Finished, 87 файлов, 245 с → "✓ Всё синхронизировано", "87 файлов за 4 мин 05 с", кнопка "Синхронизировать сейчас" enabled
  @Test fun offlineShowsReassurance() // Offline → "Нет подключения к интернету" и текст офлайна
  @Test fun authErrorOffersSignIn() // Error + accountEmail == null → "Нужно снова войти в Google", кнопка "Войти через Google" вызывает onOpenSetup
  ```
- [ ] **Step 2: Run** `./gradlew :ui:desktopTest` — FAIL.
- [ ] **Step 3: Implement.** `StatusScreen` — одна колонка по спеке §5.5, `Column` с `verticalScroll`; `LinearProgressIndicator` (неопределённый в `Scanning`/`Comparing`); «Подробнее» и «Готово» — локальные `mutableStateOf(false)`; время «сейчас» для ETA — `produceState` с тиком 1 с от `Clock.System`. Существующие элементы (аккаунт, конфликты, «Настроить Mimir») сохраняются под блоком прогресса.
- [ ] **Step 4: Run** `mise run test` — PASS. Ручная проверка на niri: первая синхронизация тестового vault показывает все блоки.
- [ ] **Step 5: Commit** `feat(ui): show detailed human-readable sync progress on status screen`.

---

### Task 13: Документация, changeset, финальная проверка

**Files:**
- Modify: `README.md`, `AGENTS.md`, `docs/superpowers/specs/2026-10-24-mimir-desktop-design.md`
- Create: `.changeset/desktop-polish.md`

- [ ] **Step 1: README.** Раздел «Google Cloud (для владельца проекта)»: Branding → App name = Mimir; два OAuth-клиента (Desktop app; TVs and Limited Input devices) в одном проекте; Publishing status «In production» и проверка бренда (`drive.file` нечувствительное, аудит не нужен); в «Testing» вход только для тестовых пользователей и refresh-токены живут 7 дней; файлы, созданные rclone с другим client ID, не видны — использовать в rclone client ID этого проекта; GitHub Secrets `MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_ID/SECRET`, `MIMIR_BUILD_GOOGLE_DEVICE_CLIENT_ID/SECRET`. Раздел «Свой OAuth-клиент»: `MIMIR_GOOGLE_DESKTOP_*`, `MIMIR_GOOGLE_DEVICE_*`, алиас старых имён; Docker-демон — только переменные.
- [ ] **Step 2: AGENTS.md.** В «Правила»: новые переменные и что вшитые значения живут только в BuildConfig `daemon`; Linux-лаунчеры выставляют `_JAVA_AWT_WM_NONREPARENTING=1` (или итог `Ruling:` Task 1); `SyncStatus` включает `Offline`, прогресс — `SyncEngine.progress`; ошибки файлов не прерывают синхронизацию.
- [ ] **Step 3: Пометка в спеке этапа 5** у пунктов про Device Code и статус: «Дополнено `2026-10-24-mimir-desktop-polish-design.md`».
- [ ] **Step 4: Changeset** `.changeset/desktop-polish.md` — `minor` для пакетов группы `fixed` (формат как в `.changeset/feat-desktop-app.md`); `pnpm run version:check` — PASS.
- [ ] **Step 5: Финальная проверка без секретов:** `env -u MIMIR_GOOGLE_CLIENT_ID -u MIMIR_GOOGLE_CLIENT_SECRET mise run test` и `mise run build` — PASS, в выводе сборки без SDK — `Android SDK not found: skipping Android targets`. Ручная приёмка спеки §7 (вход кнопкой с реальным Desktop-клиентом из `mise.local.toml`, первая синхронизация, отключение сети, файл без прав на чтение). Итоги — в `progress.md`.
- [ ] **Step 6: Commit** `docs: document Google Cloud setup, browser sign-in and sync progress` и `chore(release): add changeset for desktop polish`.
