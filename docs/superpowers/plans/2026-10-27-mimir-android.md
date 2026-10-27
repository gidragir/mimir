# Mimir Android (MVP-синхронизатор) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Рабочее Android-приложение: мастер настройки, вход через системное окно аккаунта (запасной — браузер), фоновая синхронизация ≤ ~1 мин без foreground-службы, уведомления и экраны статуса/конфликтов — на общем бэкенде.

**Architecture:** Из `daemon` выносится KMP-модуль `:runtime` (jvm + android) с фасадом `MimirRuntime : DaemonService`; платформенное — за портами (`VaultWatcherFactory`, `AccessTokenSource`, `LoginProvider`, `RuntimeSettingsStore`). `daemon`, `app-desktop`, `app-android` — composition root'ы. Android добавляет GIS-адаптеры, WorkManager-триггеры и уведомления.

**Tech Stack:** Kotlin 2.4.21 KMP, AGP 9.4.1, Compose Multiplatform 1.12, Ktor 3.6 (client + server CIO), WorkManager 2.10, `play-services-auth` (AuthorizationClient), `androidx.browser`, Robolectric, `work-testing`.

**Spec:** `docs/superpowers/specs/2026-10-27-mimir-android-design.md`

## Global Constraints

- Сборка и тесты без Android SDK и без секретов проходят; Android-цели пропускаются с `Android SDK not found: skipping Android targets`.
- Версии зависимостей — только в `gradle/libs.versions.toml`; идентификаторы — только из `gradle.properties` (`mimir.appId`, `mimir.version`, `mimir.group`).
- `Dispatchers.IO` передаётся только из точек входа (`daemon`, `app-desktop`, `app-android`); в `runtime`/`core`/`drive` диспетчер — параметр.
- `play-services-auth`, `androidx.browser`, WorkManager — только в `app-android`.
- Вшитые клиенты Google: в APK только Desktop-пара (`MIMIR_BUILD_GOOGLE_DESKTOP_*`); Device-клиент в APK не попадает. Device Code на Android не регистрируется.
- HTTP API демона сохраняет прежний JSON (маршруты `/auth/browser/*`, `/auth/device/*`, `/auth/methods`).
- Тексты UI — в `SyncTexts`, без «MediaStore», «WorkManager», «GIS».
- Схема возврата из браузера — `mimir://authorized` (без токенов и кодов).
- Пакеты: `dev.mimir.runtime`, `dev.mimir.android`. Коммиты — conventional (`feat(runtime): …`).
- Защита от удаления (`SyncAbortedException`, `stat` перед `DeleteLocal`) не ослабляется ни в одном режиме.

## Review Focus

1. **Отзыв доступа к файлам после настройки** — Worker не падает циклом и не удаляет ничего в Drive; статус «Нет доступа к файлам», баннер с кнопкой. Тест — Task 12 (`StorageAccessBannerTest`) и Task 9 (`worker returns success without sync when storage access is missing`).
2. **Двойной прогон: Worker и экран одновременно** — второй ждёт или пропускается, а не идёт параллельно. Тест — Task 5 (`runNow waits for an in-flight run instead of starting a second`).
3. **Аккаунт удалён с телефона / доступ отозван в Google** — `GisTokenSource` даёт `SignInRequired`, синхронизация стоит, уведомление «Войдите снова», без цикла повторов. Тест — Task 7 (`token throws auth error when interactive resolution is required`) и Task 10.
4. **Собственные скачивания будят триггер `MediaStore`** — повторный прогон не запускается для путей, только что записанных движком. Тест — Task 9 (`paths written by sync are ignored`).
5. **Vault на SD-карте или tree-URI облачного провайдера** — путь получается через `StorageVolume.directory`, облачный URI даёт понятную ошибку, а не падение. Тест — Task 11 (`tree uri on removable volume resolves to volume path`, `non-local provider uri yields null`).

## Инварианты Mimir

- **Платформы.** Android — Tasks 7–12, 14. Linux/Windows desktop и Docker-демон — поведение прежнее, код переезжает в `runtime` (Tasks 3–5), проверка — тесты `daemon`/`app-desktop`/`runtime` и CI-матрица; Windows руками не проверяется. JS-клиенты — неприменимо: JSON HTTP API прежний (Task 4, `RoutesTest`). Без Android SDK — Task 3 Step 4, Task 13 Step 3.
- **Дизайн.** Порт и адаптер: `AccessTokenSource` (эталон `RemoteStorage` ↔ `GoogleDriveStorage`), `VaultWatcherFactory`, `RuntimeSettingsStore`, `StorageAccess`/`SystemAccountLogin` (эталон `LocalFolderPicker`); реестр `LoginRegistry` (эталон `RemoteStorageRegistry`); composition root `MimirApplication` (эталон `DaemonServiceFactory`); `WorkerFactory` вместо статического поля.
- **IO.** Прогон без изменений — 1 вызов Change Feed + `stat`-обход; N изменённых файлов — N передач, `parallelism = 4` на `Dispatchers.IO` из `app-android`; триггер — 1 запрос к `ContentResolver` на пачку URI, посторонние пути не будят; поиск vault — глубина 2; GIS-токен кэшируется 50 минут.
- **UX.** Сценарий и состояния — спека §10; проверка глазами — Task 14 Step 2.

---

### Task 1: Проверка триггера `MediaStore` на устройстве (spike, код не коммитится)

**Files:**
- Create (временно, не коммитить): `app-android/src/debug/kotlin/dev/mimir/android/probe/MediaStoreProbeWorker.kt`
- Modify: `.superpowers/sdd/2026-10-27-mimir-android/progress.md` (git-ignored)

Требует телефона/эмулятора API 36 с Obsidian и пользователя — исполнитель согласует время.

- [ ] **Step 1:** Worker с `addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)`, `setTriggerContentUpdateDelay(10s)`, `setTriggerContentMaxDelay(60s)`; в `doWork` логирует `Log.i("MimirProbe", "${System.currentTimeMillis()} ${triggeredContentUris}")`, запрашивает `MediaStore.Files.FileColumns.DATA` для каждого URI и перепланирует себя. Ставится из `MainActivity.onCreate` в debug.
- [ ] **Step 2:** `./gradlew :app-android:installDebug`; выдать «доступ ко всем файлам»; свернуть приложение; в Obsidian изменить заметку и закрыть её; повторить 5 раз, в т.ч. с выключенным экраном.
- [ ] **Step 3:** `adb logcat -s MimirProbe` — записать в progress.md задержку правка → лог и видны ли пути `.md`.
- [ ] **Step 4:** Решение в progress.md как `Ruling:`: «триггер работает, задержка X» → Task 9 как есть; «не работает» → Task 9 сводится к периодической задаче + срочной при уходе в фон, в спеку §6.3 дописывается Ruling с новой целевой задержкой (≤ 15 мин в фоне). Удалить probe (`git status` чистый).

---

### Task 2: Порт `AccessTokenSource` в `drive`

**Files:**
- Create: `drive/src/commonMain/kotlin/dev/mimir/drive/auth/AccessTokenSource.kt`
- Modify: `drive/src/commonMain/kotlin/dev/mimir/drive/client/DriveHttpClient.kt:22-82`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/auth/RefreshingTokenSourceTest.kt`, `drive/src/commonTest/kotlin/dev/mimir/drive/auth/AccessTokenSourceContract.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface AccessTokenSource {
      /** Действующий токен; [DriveAuthException] 401 — нужен вход. */
      suspend fun token(): String
      /** Отбросить текущий токен (после 401) и получить новый. */
      suspend fun refresh(): String
      suspend fun hasCredentials(): Boolean
  }
  class RefreshingTokenSource(storage: TokenStorage, clients: GoogleAuthClients, clock: Clock = Clock.System) : AccessTokenSource
  class FakeAccessTokenSource(var tokens: ArrayDeque<String>, var requireSignIn: Boolean = false) : AccessTokenSource // commonTest
  ```
  `DriveHttpClient(httpClient, tokenSource: AccessTokenSource, clock, retryPolicy, random)` — основной конструктор; вторичный `DriveHttpClient(httpClient, tokenStorage: TokenStorage, authClients: GoogleAuthClients, clock, retryPolicy, random)` оборачивает `RefreshingTokenSource` (существующие тесты не меняются). `getValidToken()`/`forceRefreshToken()` сохраняют имена и `mapTokenErrors`, делегируя `token()`/`refresh()`.

- [ ] **Step 1: Контрактный тест** `abstract class AccessTokenSourceContract { abstract fun signedIn(): AccessTokenSource; abstract fun signedOut(): AccessTokenSource }` с тестами `token returns stored token while valid`, `refresh returns a new token`, `signed out source throws DriveAuthException with 401`, `hasCredentials reflects sign-in`. Наследники: `RefreshingTokenSourceTest` (MockEngine, `InMemoryTokenStorage`, просроченный токен → один POST на `/token`) и `FakeAccessTokenSourceTest`.
- [ ] **Step 2:** `./gradlew :drive:jvmTest --tests '*TokenSource*'` → FAIL (нет типов).
- [ ] **Step 3:** Перенести логику refresh из `DriveHttpClient` в `RefreshingTokenSource` (тот же `Mutex`, `forKind(tokens.clientKind)`); `DriveHttpClient` получает конструкторы выше.
- [ ] **Step 4:** `./gradlew :drive:allTests` → PASS (включая `DriveHttpClientTest`, `GoogleAuthClientsTest` без правок).
- [ ] **Step 5:** `git commit -m "refactor(drive): AccessTokenSource strategy for OAuth tokens"`

---

### Task 3: Модуль `:runtime` и перенос инфраструктуры из `daemon`

**Files:**
- Create: `runtime/build.gradle.kts`, `runtime/package.json` (носитель версии, как у соседних модулей)
- Modify: `settings.gradle.kts` (`include(":runtime")`), `daemon/build.gradle.kts` (`implementation(project(":runtime"))`), `.changeset/config.json` (группа `fixed`)
- Move (`git mv`, пакет `dev.mimir.daemon` → `dev.mimir.runtime`): `SyncScheduler.kt`, `JsonSyncStateStore.kt`, `ConflictFiles.kt`, `Canary.kt`, `AtomicFiles.kt`, `FileTokenStorage.kt` → `runtime/src/commonMain/kotlin/dev/mimir/runtime/`; их тесты (`SyncSchedulerTest`, `JsonSyncStateStoreTest`, `CanaryCheckTest`, `FileTokenStorageTest`) → `runtime/src/jvmTest/kotlin/dev/mimir/runtime/`

**Interfaces:**
- Produces: модуль `:runtime` (`kotlin("multiplatform")`, `jvm()`, `id("mimir.android-support")`, `kotlin("plugin.serialization")`); `commonMain` зависит от `:core`, `:drive`, `:client`, ktor client core, ktor server core + cio, serialization-json. Классы выше — в `dev.mimir.runtime`, `public`.

- [ ] **Step 1:** Создать модуль и перенести файлы; обновить импорты в `daemon`.
- [ ] **Step 2:** `./gradlew :runtime:compileKotlinJvm :runtime:compileAndroidMain` (последнее — только при SDK). Если `commonMain` не видит `java.nio`/`java.util.concurrent` — перенести исходники в `src/jvmMain/kotlin`, добавить `androidMain { kotlin.srcDir("src/jvmMain/kotlin") }`, записать `Ruling:` в progress.md.
- [ ] **Step 3:** `./gradlew :runtime:jvmTest :daemon:test` → PASS.
- [ ] **Step 4:** Без SDK: `ANDROID_HOME= ANDROID_SDK_ROOT= ./gradlew -Pandroid.sdk.dir= :runtime:jvmTest` — в выводе `Android SDK not found: skipping Android targets` (способ отключения SDK — как в скилле `mimir-build-verify`).
- [ ] **Step 5:** `git commit -m "refactor: extract runtime module from daemon"`

---

### Task 4: `LoginProvider` и единый контракт входа

**Files:**
- Create: `runtime/src/commonMain/kotlin/dev/mimir/runtime/login/{LoginProvider.kt, LoginRegistry.kt, BrowserLoginProvider.kt, DeviceCodeLoginProvider.kt}`
- Move: `daemon/.../BrowserLoginReceiver.kt` → `runtime/.../login/BrowserLoginReceiver.kt`
- Modify: `client/.../DaemonDto.kt`, `client/.../DaemonService.kt`, `client/.../HttpDaemonClient.kt`, `daemon/.../DaemonRuntime.kt:252-352`, `daemon/.../Routes.kt:151-200`, `daemon/.../GoogleClients.kt` (`loginMethodsFor` удаляется), `ui/.../model/{MimirUiModel,DaemonServiceUiModel,EngineMimirUiModel,FakeMimirUiModel}.kt`, `ui/.../screens/setup/GoogleStep.kt`
- Test: `runtime/src/jvmTest/.../login/LoginRegistryTest.kt`, перенос `daemon/.../BrowserLoginTest.kt` → `runtime/src/jvmTest/.../login/BrowserLoginProviderTest.kt`; правки `HttpDaemonClientTest`, `ConflictAndDeviceAuthRoutesTest`, `RoutesTest`, `FakeDaemonService`, `DaemonServiceUiModelTest`, `SetupWizardScreenTest`

**Interfaces:**
- Produces (`:client`):
  ```kotlin
  enum class DaemonLoginMethod { SystemAccount, Browser, DeviceCode } // порядок = приоритет в UI
  sealed interface DaemonLoginStart {
      data class OpenUrl(val url: String) : DaemonLoginStart
      data class ShowDeviceCode(val code: DaemonDeviceCodeDto) : DaemonLoginStart
      data object Started : DaemonLoginStart
  }
  sealed interface DaemonLoginPoll {
      data object Pending : DaemonLoginPoll
      data class Authorized(val email: String?) : DaemonLoginPoll
      data class Failed(val reason: String) : DaemonLoginPoll
  }
  // DaemonService: вместо start/poll Browser/Device:
  suspend fun loginMethods(): Set<DaemonLoginMethod>
  suspend fun startLogin(method: DaemonLoginMethod): DaemonLoginStart
  suspend fun pollLogin(): DaemonLoginPoll
  ```
  `DaemonDevicePoll`, `DaemonBrowserLoginPoll` удаляются. `HttpDaemonClient.startLogin(SystemAccount)` → `UnsupportedOperationException`; `pollLogin()` идёт в маршрут последнего начатого способа (device — с сохранённым `deviceCode`; `Expired` → `Failed`).
- Produces (`:runtime`):
  ```kotlin
  interface LoginProvider {
      val method: DaemonLoginMethod
      val tokenSource: AccessTokenSource
      suspend fun start(): DaemonLoginStart
      /** Authorized — учётные данные уже сохранены провайдером. */
      suspend fun poll(): DaemonLoginPoll
      suspend fun signOut()
      fun close() {}
  }
  class LoginRegistry(providers: List<LoginProvider>) {
      fun methods(): Set<DaemonLoginMethod>
      operator fun get(method: DaemonLoginMethod): LoginProvider?
  }
  class BrowserLoginProvider(client: GoogleAuthClient, tokenStorage: TokenStorage, scope: CoroutineScope, timeout: Duration = 5.minutes) : LoginProvider
  class DeviceCodeLoginProvider(client: GoogleAuthClient, tokenStorage: TokenStorage) : LoginProvider
  ```
  Оба провайдера используют один `RefreshingTokenSource(tokenStorage, clients)` и `client.fetchAccount` для email. Перезапуск браузерной попытки — как сейчас (`job.cancel` + `join`).
- Produces (`:ui`): `UiLoginMethod { SystemAccount, Browser, DeviceCode }`, `UiLoginStart { OpenUrl(url), ShowDeviceCode(UiDeviceCode), Started }`, `UiLoginPoll { Pending, Authorized(email), Failed(reason) }`; `MimirUiModel.startLogin(method)`, `pollLogin()` вместо четырёх методов.
- `DaemonRuntime` (пока в `daemon`) держит `LoginRegistry`; `startLogin` запоминает активного провайдера; первый `Authorized` из `pollLogin` → `completeLogin(email, method)`: `account.json` = `{"email":…, "method":"browser"|"device_code"|"system_account"}`, затем `rebuildLenient()` (ровно один раз на попытку).
- Ruling (записать в progress.md): `DeviceCodeLoginProvider` живёт в `runtime`, а не в `daemon` (спека §4.1) — Android его просто не регистрирует.

- [ ] **Step 1: Тесты** `LoginRegistryTest`: `methods lists registered providers`, `get returns null for unregistered method`. `BrowserLoginProviderTest` — перенесённые сценарии `BrowserLoginTest` через `start()`/`poll()`. `DaemonRuntimeTest`: `authorized poll writes account json with method and rebuilds once`. `RoutesTest`/`HttpDaemonClientTest`: JSON маршрутов не изменился (существующие ожидания строк `"pending"`, `"authorized"`, `"failed"`, `"expired"`).
- [ ] **Step 2:** `./gradlew :runtime:jvmTest :daemon:test :client:allTests` → FAIL (нет типов).
- [ ] **Step 3:** Реализовать провайдеры и реестр; `DaemonRuntime`, `Routes`, `HttpDaemonClient` на новый контракт.
- [ ] **Step 4:** `GoogleStep`: кнопка для первого доступного способа в порядке `SystemAccount → Browser → DeviceCode`; `OpenUrl` → `LocalUriHandler`; `ShowDeviceCode` → прежний экран кода; `Started`/`Pending` → опрос `pollLogin()` раз в 1 с; `Failed` → текст ошибки и «Попробовать снова». `SystemAccount` на этом шаге только объявлен (вызов окна — Task 12).
- [ ] **Step 5:** `./gradlew :runtime:jvmTest :daemon:test :client:allTests :ui:desktopTest :app-desktop:test` → PASS.
- [ ] **Step 6:** `git commit -m "refactor: unify Google login behind LoginProvider registry"`

---

### Task 5: `DaemonRuntime` → `MimirRuntime` в `runtime`

**Files:**
- Move: `daemon/.../DaemonRuntime.kt` → `runtime/.../MimirRuntime.kt`; тесты `DaemonRuntimeTest`, `ConfigureVaultTest`, `InspectVaultTest`, `ConflictCacheTest`, `ReactiveConflictTrackingTest`, `IdleVaultIoTest` → `runtime/src/jvmTest/...`
- Create: `runtime/.../{RuntimeConfig.kt, RuntimeSettingsStore.kt, JsonRuntimeSettingsStore.kt, VaultWatcherFactory.kt, EngineAssembler.kt, CloudStorageFactory.kt}`
- Modify: `runtime/.../SyncScheduler.kt` (`runNow`, `pendingLocalChanges`), `daemon/.../{Main.kt, DaemonConfig.kt}` (+ `DaemonSettingsStore`), `app-desktop/.../DaemonServiceFactory.kt`
- Test: `runtime/src/jvmTest/.../{EngineAssemblerTest.kt, MimirRuntimeModesTest.kt, JsonRuntimeSettingsStoreTest.kt}`, дополнение `SyncSchedulerTest`

**Interfaces:**
- Consumes: `LoginRegistry`, `LoginProvider.tokenSource` (Task 4), `AccessTokenSource` (Task 2).
- Produces:
  ```kotlin
  data class RuntimeConfig(val dataDir: java.nio.file.Path, val vaultPath: String?, val syncIntervalSeconds: Long = 30, val syncParallelism: Int = 4, val rootFolder: String = "MimirVault")
  interface RuntimeSettingsStore { fun load(): RuntimeConfig; fun saveVaultPath(path: String) }
  class JsonRuntimeSettingsStore(dataDir: java.nio.file.Path) : RuntimeSettingsStore // settings.json через writeFileAtomically; используется Android
  fun interface VaultWatcherFactory { fun create(root: kotlinx.io.files.Path, rules: SyncRules, debounce: Duration): VaultWatcher }
  fun interface CloudStorageFactory { fun create(httpClient: HttpClient, tokenSource: AccessTokenSource, config: RuntimeConfig): ChangeFeedStorage? } // замена RemoteStorageFactory
  class MimirRuntime(
      settings: RuntimeSettingsStore,
      logins: LoginRegistry,
      watcherFactory: VaultWatcherFactory,
      passwordProvider: suspend () -> String?,
      ioDispatcher: CoroutineDispatcher,
      httpClient: HttpClient,
      cloudFactory: CloudStorageFactory = googleDriveFactory,
      scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
      conflictScanInterval: Duration = 5.seconds,
      localVaultFactory: (Path) -> LocalVault = { FileSystemLocalVault(it, ioDispatcher = ioDispatcher) },
  ) : DaemonService {
      suspend fun initialize()
      /** false — планировщик и наблюдатели остановлены (Android в фоне). */
      suspend fun setInteractive(active: Boolean)
      /** Один прогон до конца; false — синхронизация не настроена или ждёт подтверждения первого слияния. */
      suspend fun syncOnce(): Boolean
      val pendingLocalChanges: StateFlow<Boolean>
      /** Пути, записанные/удалённые движком локально за последние 2 минуты (эхо для триггеров). */
      fun recentlyWrittenBySync(): Set<String>
      suspend fun signOut()
      fun close()
  }
  class EngineAssembler(...) { suspend fun assemble(vaultPath: String, cipher: Cipher, tokenSource: AccessTokenSource): AssembledEngine? }
  data class AssembledEngine(val engine: DefaultSyncEngine, val scheduler: SyncScheduler, val watcher: VaultWatcher, val remote: EncryptedRemoteStorage)
  // SyncScheduler:
  suspend fun runNow(): Boolean          // ждёт завершения прогона под syncMutex
  val pendingLocalChanges: StateFlow<Boolean> // true по локальному событию, false после успешного прогона, начатого после него
  ```
  Активный токен-источник: `logins[account.method]?.tokenSource`; `account.json` без `method` (старый формат) читается как `browser`, если `OAuthTokens.clientKind == Desktop`, иначе `device_code`.
- `daemon`: `DaemonSettingsStore(config: DaemonConfig)` поверх `config.json`; `passwordProvider = { config.resolvePassword() }`; регистрирует Browser/Device-провайдеры по `googleCredentials()`; `watcherFactory` — `NioWatchServiceVaultWatcher`; `ioDispatcher = Dispatchers.IO`. Ktor-сервер и `Routes` остаются в `daemon`.
- `MimirRuntime` ≤ ~300 строк: `rebuild`/`teardown` → `EngineAssembler`; конфликты → `ConflictTracker` (`runtime/.../ConflictTracker.kt`), если иначе не укладывается.

- [ ] **Step 1: Тесты** `SyncSchedulerTest`: `runNow waits for an in-flight run instead of starting a second` (фейковый движок с задержкой; 2 вызова → 2 последовательных прогона, никогда не параллельно), `pendingLocalChanges clears after successful run`. `MimirRuntimeModesTest`: `setInteractive false stops watcher and scheduler`, `syncOnce runs while not interactive`, `syncOnce returns false while initial merge is held`, `recentlyWrittenBySync lists downloaded paths from last progress`, `signOut calls provider signOut, deletes account json and keeps index and vault`. `EngineAssemblerTest`: `returns null without credentials`, `uses watcher from factory`. `JsonRuntimeSettingsStoreTest`: `vault path survives reload`.
- [ ] **Step 2:** `./gradlew :runtime:jvmTest` → FAIL.
- [ ] **Step 3:** Перенос и выделение классов; `DaemonRuntime` исчезает, `daemon/Main` и `DaemonServiceFactory` собирают `MimirRuntime`.
- [ ] **Step 4:** `./gradlew :runtime:jvmTest :daemon:test :app-desktop:test` → PASS; `wc -l runtime/src/*/kotlin/dev/mimir/runtime/MimirRuntime.kt` ≤ ~300.
- [ ] **Step 5:** Ручной дымовой тест: `mise run daemon` с существующим конфигом стартует, `curl 127.0.0.1:47821/health` отвечает.
- [ ] **Step 6:** `git commit -m "refactor(runtime): MimirRuntime with platform ports and EngineAssembler"`

---

### Task 6: Вид ошибки в `SyncStatus.Error`

**Files:**
- Modify: `core/src/commonMain/kotlin/dev/mimir/core/sync/SyncEngine.kt`, `core/.../sync/DefaultSyncEngine.kt:70-76`, `ui/.../screens/SyncTexts.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/SyncEngineProgressTest.kt`

**Interfaces:**
- Produces: `enum class SyncErrorKind { Other, SignInRequired, Aborted }`; `data class Error(val message: String, val kind: SyncErrorKind = SyncErrorKind.Other)`. `RemoteAuthException` → `SignInRequired`; `SyncAbortedException` → `Aborted`. Ruling: вид ошибки — поле, а не новый статус, чтобы не ломать существующие `when`.

- [ ] **Step 1: Тесты** `auth failure sets SignInRequired error`, `empty remote with index sets Aborted error`.
- [ ] **Step 2:** `./gradlew :core:jvmTest --tests '*SyncEngineProgressTest*'` → FAIL.
- [ ] **Step 3:** Реализовать; `SyncTexts`: `SignInRequired` → «Войдите в Google снова», `Aborted` → «Синхронизация остановлена: папка в Drive пуста».
- [ ] **Step 4:** `./gradlew :core:allTests :ui:desktopTest` → PASS.
- [ ] **Step 5:** `git commit -m "feat(core): classify sync errors (sign-in required, aborted)"`

---

### Task 7: GIS-адаптеры в `app-android`

**Files:**
- Modify: `gradle/libs.versions.toml` (`play-services-auth`, `robolectric`, `androidx-test-core`), `app-android/build.gradle.kts` (зависимости, `testOptions.unitTests.isIncludeAndroidResources = true`)
- Create: `app-android/src/main/kotlin/dev/mimir/android/auth/{GisAuthorizer.kt, PlayServicesGisAuthorizer.kt, GisTokenSource.kt, SystemAccountLoginProvider.kt}`
- Test: `app-android/src/test/kotlin/dev/mimir/android/auth/{GisTokenSourceTest.kt, SystemAccountLoginProviderTest.kt}`

**Interfaces:**
- Consumes: `AccessTokenSource` (Task 2; тестовые наборы `drive` в `app-android` недоступны, поэтому `GisTokenSourceTest` повторяет 4 сценария контракта сам), `LoginProvider` (Task 4).
- Produces:
  ```kotlin
  interface GisAuthorizer {
      /** Токен без UI; null — нужен интерактивный вход (hasResolution). */
      suspend fun authorizeSilently(): String?
      suspend fun clearToken(token: String)
      suspend fun revoke()
      fun isAvailable(): Boolean // GoogleApiAvailability == SUCCESS
  }
  class PlayServicesGisAuthorizer(context: Context, accountPrefs: SharedPreferences) : GisAuthorizer
      // scopes = GoogleAuthConfig.scopes; setAccount(Account(email,"com.google")) из accountPrefs["gis_account"]
  class GisTokenSource(authorizer: GisAuthorizer, clock: Clock = Clock.System, ttl: Duration = 50.minutes) : AccessTokenSource
  class SystemAccountLoginProvider(tokenSource: GisTokenSource, fetchEmail: suspend (accessToken: String) -> String?) : LoginProvider
      // method = SystemAccount; start() → Started; poll(): token() → fetchEmail → Authorized(email); ошибка → Failed("Не удалось войти в Google")
  ```
  `fetchEmail` = `GoogleAuthClient.fetchAccount(token).email` (userinfo; scope `email` уже в `GoogleAuthConfig.scopes`). Ruling: вместо `about.get` из спеки §5.1 — существующий `fetchAccount`.

- [ ] **Step 1: Тесты** (`FakeGisAuthorizer`): `token is cached for ttl`, `token re-authorizes silently after ttl`, `refresh clears cached token and authorizes again`, `token throws auth error when interactive resolution is required` (`DriveAuthException`, 401), `hasCredentials false when silent authorization needs UI`; `SystemAccountLoginProvider`: `poll returns Authorized with email`, `poll returns Failed when silent authorization needs UI`, `signOut revokes access`.
- [ ] **Step 2:** `./gradlew :app-android:testDebugUnitTest --tests '*auth*'` → FAIL.
- [ ] **Step 3:** Реализовать; `PlayServicesGisAuthorizer` — `Identity.getAuthorizationClient(context).authorize(request).await()` (kotlinx-coroutines-play-services), `clearToken`/`revokeAccess` по API клиента.
- [ ] **Step 4:** тесты → PASS.
- [ ] **Step 5:** `git commit -m "feat(android): GIS token source and system account login provider"`

---

### Task 8: Composition root Android и Worker'ы

**Files:**
- Create: `app-android/src/main/kotlin/dev/mimir/android/{MimirApplication.kt, AndroidRuntimeFactory.kt}`, `app-android/src/main/kotlin/dev/mimir/android/work/{SyncWorkScheduler.kt, MimirWorkerFactory.kt}`
- Modify: `app-android/src/main/kotlin/dev/mimir/android/MimirSyncWorker.kt` → `work/MimirSyncWorker.kt` (без `companion var syncEngineProvider`), `AndroidManifest.xml` (`android:name=".MimirApplication"`, удалить `androidx.work` InitializationProvider через `tools:node="remove"`), `app-android/build.gradle.kts` (`buildConfigField` `GOOGLE_DESKTOP_CLIENT_ID/SECRET` из `providers.environmentVariable("MIMIR_BUILD_GOOGLE_DESKTOP_*").orElse("")`, `lifecycle-process`, `work-testing`, `:runtime`)
- Test: `app-android/src/test/kotlin/dev/mimir/android/work/{SyncWorkSchedulerTest.kt, MimirSyncWorkerTest.kt}`

**Interfaces:**
- Consumes: `MimirRuntime`, `JsonRuntimeSettingsStore`, `LoginRegistry`, `BrowserLoginProvider` (Task 4–5), `GisTokenSource`, `SystemAccountLoginProvider`, `PlayServicesGisAuthorizer` (Task 7), `AndroidFileObserverVaultWatcher`, `AndroidKeystoreSecretStore`, `SecretStoreTokenStorage`.
- Produces:
  ```kotlin
  class MimirApplication : Application(), Configuration.Provider { val runtime: MimirRuntime /* by lazy */; val workScheduler: SyncWorkScheduler }
  object AndroidRuntimeFactory { fun create(context: Context): MimirRuntime }
      // dataDir = context.filesDir; password = AndroidKeystoreSecretStore["vault_password"];
      // providers: SystemAccount (если authorizer.isAvailable()), Browser (если BuildConfig.GOOGLE_DESKTOP_CLIENT_ID не пуст)
  class SyncWorkScheduler(workManager: WorkManager, wifiOnly: () -> Boolean) {
      fun schedulePeriodic()   // "mimir-periodic-sync", 15 мин, ExistingPeriodicWorkPolicy.UPDATE
      fun scheduleExpedited()  // "mimir-expedited-sync", ExistingWorkPolicy.KEEP
      fun scheduleVaultTrigger() // "mimir-vault-change" (Task 9)
      companion object { const val PERIODIC = "mimir-periodic-sync"; const val EXPEDITED = "mimir-expedited-sync"; const val VAULT_CHANGE = "mimir-vault-change" }
  }
  class MimirWorkerFactory(runtime: () -> MimirRuntime, scheduler: () -> SyncWorkScheduler) : WorkerFactory()
  class MimirSyncWorker(ctx, params, runtime: MimirRuntime) : CoroutineWorker // syncOnce(); исключение → retry при runAttemptCount < 3, иначе failure
  ```
  Сеть: `NetworkType.CONNECTED`, при `SyncRules.wifiOnly` — `UNMETERED`. `ProcessLifecycleOwner`: `ON_START` → `runtime.setInteractive(true)`; `ON_STOP` → `setInteractive(false)` и `scheduleExpedited()`, если `pendingLocalChanges.value`. Пароль: `DaemonServiceUiModel(rememberPassword = { secretStore.put("vault_password", it) })` (Task 12).

- [ ] **Step 1: Тесты** (`WorkManagerTestInitHelper`, Robolectric): `periodic work is unique and requires network`, `wifiOnly switches constraint to unmetered`, `expedited work keeps existing request`; `MimirSyncWorkerTest` (`TestListenableWorkerBuilder` + фабрика с фейковым runtime-фасадом `SyncRunner { suspend fun syncOnce(): Boolean }`): `success after sync`, `retry on exception below three attempts`, `failure after three attempts`. Worker зависит от `fun interface SyncRunner`, а не от `MimirRuntime` напрямую.
- [ ] **Step 2:** `./gradlew :app-android:testDebugUnitTest --tests '*work*'` → FAIL.
- [ ] **Step 3:** Реализовать; `ManifestPermissionsTest` дополнить: `application name is MimirApplication`, `no foreground service declared`.
- [ ] **Step 4:** `./gradlew :app-android:testDebugUnitTest :app-android:assembleDebug` → PASS.
- [ ] **Step 5:** `git commit -m "feat(android): application composition root and WorkManager sync"`

---

### Task 9: Триггер изменений vault (`MediaStore`)

Объём зависит от `Ruling` Task 1; при «не работает» задача сводится к Step 5–6 без `VaultChangeWorker`.

**Files:**
- Create: `app-android/src/main/kotlin/dev/mimir/android/work/{VaultChangeWorker.kt, VaultChangeFilter.kt, TriggeredPathResolver.kt}`
- Modify: `work/SyncWorkScheduler.kt` (`scheduleVaultTrigger`), `MimirWorkerFactory.kt`
- Test: `app-android/src/test/kotlin/dev/mimir/android/work/{VaultChangeFilterTest.kt, VaultChangeWorkerTest.kt}`

**Interfaces:**
- Produces:
  ```kotlin
  object VaultChangeFilter {
      fun relevant(absolutePaths: List<String>, vaultRoot: String, rules: SyncRules, writtenBySync: Set<String>): Boolean
  }
  fun interface TriggeredPathResolver { fun paths(uris: List<Uri>): List<String> } // один query DATA с "_id IN (...)"
  class VaultChangeWorker(ctx, params, runner: SyncRunner, scheduler: SyncWorkScheduler, resolver: TriggeredPathResolver, vault: () -> VaultContext?) : CoroutineWorker
  data class VaultContext(val root: String, val rules: SyncRules, val writtenBySync: Set<String>, val storageAccess: Boolean)
  ```
  `scheduleVaultTrigger`: `addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)`, `setTriggerContentUpdateDelay(10s)`, `setTriggerContentMaxDelay(60s)`, `ExistingWorkPolicy.REPLACE`. Worker первым делом перепланирует триггер.

- [ ] **Step 1: Тесты** `VaultChangeFilterTest`: `path inside vault is relevant`, `path outside vault is ignored`, `excluded path is ignored` (`.obsidian/workspace.json` по правилам по умолчанию), `paths written by sync are ignored`, `vault root prefix does not match sibling folder` (`/Docs/Vault2/a.md` при корне `/Docs/Vault`). `VaultChangeWorkerTest`: `reschedules trigger before syncing`, `syncs when a relevant path changed`, `worker returns success without sync when storage access is missing`.
- [ ] **Step 2:** `./gradlew :app-android:testDebugUnitTest --tests '*VaultChange*'` → FAIL.
- [ ] **Step 3:** Реализовать; `MimirApplication.onCreate` вызывает `scheduleVaultTrigger()` и `schedulePeriodic()`, когда vault настроен.
- [ ] **Step 4:** тесты → PASS.
- [ ] **Step 5:** `git commit -m "feat(android): wake sync on vault changes via MediaStore trigger"`

---

### Task 10: Уведомления со статусом

**Files:**
- Create: `app-android/src/main/kotlin/dev/mimir/android/notify/{StatusNotifier.kt, NotificationContent.kt}`
- Modify: `MimirApplication.kt` (подписка на `runtime.status`/`progress`/`conflictsFlow`), `ui/.../screens/SyncTexts.kt` (тексты уведомлений)
- Test: `app-android/src/test/kotlin/dev/mimir/android/notify/NotificationContentTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class Channel { Status, Attention } // id "mimir-status" (IMPORTANCE_LOW), "mimir-attention" (IMPORTANCE_DEFAULT)
  enum class Destination { Status, Conflicts, Login }
  data class NotificationContent(val channel: Channel, val title: String, val text: String, val destination: Destination, val severity: Int)
  fun notificationFor(status: SyncStatus, conflicts: Int, pendingLocal: Boolean, lastSync: Instant?): NotificationContent?
  class StatusNotifier(context: Context) { fun show(content: NotificationContent) } // id 1 = статус, id 2 = внимание; PendingIntent → MainActivity с extra "destination"
  ```
  Тексты: «Всё синхронизировано · HH:mm», «Есть неотправленные изменения», «Нет сети — изменения отправятся позже», «Конфликт: N файл(а/ов)», «Войдите в Google снова», «Синхронизация остановлена: папка в Drive пуста». Обновление — по `SyncPhase.Finished` и смене статуса, не по каждому шагу. Смахнутое уведомление (`setDeleteIntent` → запомнить `severity`) показывается снова только при большем `severity`.

- [ ] **Step 1: Тесты** `idle maps to status channel with time`, `conflicts map to attention channel with plural form` (1 файл / 2 файла / 5 файлов), `sign in required maps to login destination`, `aborted maps to attention`, `offline with pending changes says will send later`, `not configured yields no notification`.
- [ ] **Step 2:** FAIL → **Step 3:** реализовать (`POST_NOTIFICATIONS` не выдан — `show` молча ничего не делает) → **Step 4:** `./gradlew :app-android:testDebugUnitTest --tests '*notify*'` PASS.
- [ ] **Step 5:** `git commit -m "feat(android): status and attention notifications"`

---

### Task 11: Доступ к файлам и выбор vault

**Files:**
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/platform/{StorageAccess.kt, VaultDiscovery.kt}`, `ui/src/commonMain/kotlin/dev/mimir/ui/screens/setup/AccessStep.kt`, `app-android/src/main/kotlin/dev/mimir/android/platform/{AndroidStorageAccess.kt, AndroidFolderPicker.kt, TreeUriPaths.kt, FileSystemVaultDiscovery.kt}`
- Modify: `ui/.../screens/SetupWizardScreen.kt` (шаг 0, только при `LocalStorageAccess.current?.granted == false`), `ui/.../screens/setup/VaultStep.kt` (список найденных)
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/AccessStepTest.kt`, `app-android/src/test/kotlin/dev/mimir/android/platform/{TreeUriPathsTest.kt, FileSystemVaultDiscoveryTest.kt}`

**Interfaces:**
- Produces (`ui`, по образцу `LocalFolderPicker`):
  ```kotlin
  interface StorageAccess { val granted: StateFlow<Boolean>; fun requestAllFiles(); fun requestNotifications() }
  val LocalStorageAccess = staticCompositionLocalOf<StorageAccess?> { null } // null — шага нет (desktop, wasm)
  interface VaultDiscovery { suspend fun findVaults(): List<String> }
  val LocalVaultDiscovery = staticCompositionLocalOf<VaultDiscovery?> { null }
  ```
- Produces (`app-android`): `TreeUriPaths.toPath(treeDocumentId: String, volumes: Map<String, String>): String?` — `primary:Documents/Vault` → `<primary dir>/Documents/Vault`; `<UUID>:Notes` → `<volume dir>/Notes`; неизвестный том/не-`externalstorage` authority → `null`. `FileSystemVaultDiscovery(roots: List<File>, maxDepth = 2)` — папки, содержащие `.obsidian`. `AndroidStorageAccess` — `Environment.isExternalStorageManager()`, перепроверка в `ON_RESUME`, `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` с `package:` URI.

- [ ] **Step 1: Тесты** `AccessStepTest`: `shows grant button when access missing`, `advances when access becomes granted`, `notification refusal does not block`. `TreeUriPathsTest`: `primary tree uri resolves to primary path`, `tree uri on removable volume resolves to volume path`, `non-local provider uri yields null`. `FileSystemVaultDiscoveryTest` (temp dir): `finds vault two levels deep`, `ignores deeper vaults`, `ignores folders without obsidian dir`.
- [ ] **Step 2:** FAIL → **Step 3:** реализовать; ошибка непереводимого URI в `VaultStep`: «Эта папка не на телефоне. Выберите папку во внутренней памяти или на карте памяти» → **Step 4:** `./gradlew :ui:desktopTest :app-android:testDebugUnitTest` PASS.
- [ ] **Step 5:** `git commit -m "feat(android): storage access step, vault discovery and folder picker"`

---

### Task 12: Вход и экраны в `MainActivity`

**Files:**
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/platform/SystemAccountLogin.kt`, `ui/src/commonMain/kotlin/dev/mimir/ui/screens/AndroidBanners.kt` (общие баннеры, видимость — по `LocalStorageAccess` и `SyncErrorKind`), `app-android/src/main/kotlin/dev/mimir/android/{MainViewModel.kt, ActivitySystemAccountLogin.kt, CustomTabsUriHandler.kt}`, `app-android/src/main/kotlin/dev/mimir/android/platform/AndroidNetworkState.kt` (`NetworkState` на `ConnectivityManager.registerDefaultNetworkCallback`; `NOT_METERED` → `WIFI`; источник `UiBackgroundSync.waitingForWifi`)
- Modify: `app-android/.../MainActivity.kt`, `AndroidManifest.xml` (intent-filter `mimir://authorized`, `launchMode="singleTop"`), `ui/.../screens/setup/GoogleStep.kt`, `ui/.../screens/StatusScreen.kt` (баннеры + строка фона), `ui/.../MimirApp.kt` (параметр `initialDestination`), `ui/.../model/MimirUiModel.kt` (`backgroundSync: StateFlow<UiBackgroundSync?>` с умолчанием `null`)
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/{StorageAccessBannerTest.kt, GoogleStepSystemAccountTest.kt}`, `app-android/src/test/.../ManifestPermissionsTest.kt`

**Interfaces:**
- Consumes: `UiLoginMethod.SystemAccount`, `startLogin`/`pollLogin` (Task 4), `SyncErrorKind` (Task 6), `StorageAccess` (Task 11), `Destination` (Task 10).
- Produces:
  ```kotlin
  sealed interface SystemLoginResult { data object Granted; data object Cancelled; data class Unavailable(val reason: String) }
  interface SystemAccountLogin { suspend fun authorize(): SystemLoginResult }
  val LocalSystemAccountLogin = staticCompositionLocalOf<SystemAccountLogin?> { null }
  data class UiBackgroundSync(val enabled: Boolean, val lastRun: Instant?, val waitingForWifi: Boolean)
  class MainViewModel(app: Application) : AndroidViewModel(app) { val model: MimirUiModel } // DaemonServiceUiModel(runtime, viewModelScope, rememberPassword = Keystore)
  ```
  `GoogleStep`: для `SystemAccount` — `authorize()`; `Granted` → `startLogin(SystemAccount)` + опрос; `Cancelled` → остаться на шаге без ошибки; `Unavailable` → автоматически `startLogin(Browser)`, если он есть, иначе текст «Вход через Google недоступен на этом устройстве». `ActivitySystemAccountLogin` — `rememberLauncherForActivityResult(StartIntentSenderForResult)`, при `Granted` сохраняет `toGoogleSignInAccount()?.email` в `accountPrefs["gis_account"]`. `CustomTabsUriHandler` — `CustomTabsIntent`, при `ActivityNotFoundException` — `ACTION_VIEW`. Deep link `destination` и `mimir://authorized` — в `onCreate`/`onNewIntent`; «Назад» в мастере — `BackHandler` на предыдущий шаг.

- [ ] **Step 1: Тесты** `StorageAccessBannerTest`: `banner with grant button when access revoked after setup`, `sign in banner for SignInRequired error`, `no banners on desktop (locals null)`. `GoogleStepSystemAccountTest` (фейковый `SystemAccountLogin`): `granted starts system account login and polls`, `cancelled stays on step without error`, `unavailable falls back to browser`. `ManifestPermissionsTest`: `main activity handles mimir authorized deep link`.
- [ ] **Step 2:** FAIL → **Step 3:** реализовать → **Step 4:** `./gradlew :ui:desktopTest :app-android:testDebugUnitTest :app-android:assembleDebug` PASS.
- [ ] **Step 5:** `git commit -m "feat(android): wire setup wizard, system account login and status banners"`

---

### Task 13: Сборка, иконка, документация, CI

**Files:**
- Modify: `app-android/build.gradle.kts` (`versionCode` из `mimir.version`: `major*10000 + minor*100 + patch`), `app-android/src/main/res/` (adaptive icon `mipmap-anydpi-v26/ic_launcher.xml` с foreground/background/monochrome; манифест на `@mipmap/ic_launcher`), `.github/workflows/ci.yml` (`:runtime:allTests`, `:app-android:testDebugUnitTest`), `.github/workflows/release.yml` (env `MIMIR_BUILD_GOOGLE_DESKTOP_CLIENT_ID/SECRET` для job `apk`), `README.md` (Android: установка, Android OAuth-клиенты с SHA-1 debug/release в том же проекте GCP, `keytool -list -v` для debug-ключа), `AGENTS.md` (`runtime` в «Структуре» и «Карте кода»; правило про вшитые клиенты: Desktop-пара допустима в BuildConfig `app-android`), `docs/superpowers/specs/2026-10-27-mimir-android-design.md` (§8 Rulings из progress.md), `.changeset/*.md` (`pnpm changeset`, minor)
- Test: `build-logic/src/test/kotlin/dev/mimir/build/VersionCodeTest.kt` (`versionCodeOf(version: String): Int`; `1.4.12 → 10412`, `0.1.0 → 100`, `1.2.3-beta.1 → 10203`; в `build-logic/build.gradle.kts` добавить `testImplementation(kotlin("test"))`, если тестов ещё нет)

- [ ] **Step 1:** Тест `versionCodeOf` → FAIL → реализовать в `build-logic/src/main/kotlin/dev/mimir/build/VersionCode.kt` → PASS (`./gradlew -p build-logic test`).
- [ ] **Step 2:** Иконка, README, AGENTS.md, Rulings в спеку, changeset.
- [ ] **Step 3:** `mise run build` (с SDK) и без SDK — оба зелёные; `./gradlew :app-android:assembleRelease` без ключа даёт неподписанный APK.
- [ ] **Step 4:** `git commit -m "chore(android): versionCode, adaptive icon, CI and docs"`

---

### Task 14: Проверка на устройстве (с пользователем)

- [ ] **Step 1:** `./gradlew :app-android:installDebug` на эмулятор API 36 с Play Services и на эмулятор/образ без Play Services.
- [ ] **Step 2:** Пройти мастер: «Доступ» → найденный vault → системное окно аккаунта (без Play Services — Custom Tab и возврат по `mimir://authorized`) → пароль → первое слияние → статус. Скриншоты в светлой и тёмной теме, ширина телефона.
- [ ] **Step 3:** Правка в Obsidian → свернуть Mimir → изменение в Drive (через desktop-клиент) ≤ ~1 мин (или по Ruling Task 1). Правка на desktop → на телефоне после периодического прогона.
- [ ] **Step 4:** Отозвать «доступ ко всем файлам» → баннер, Worker не удаляет ничего; отозвать доступ приложения в аккаунте Google → уведомление «Войдите снова»; смахнуть статус-уведомление → не возвращается, пока статус не ухудшится.
- [ ] **Step 5:** Отчёт по четырём пунктам «Инварианты Mimir» (Платформы, Дизайн, IO, UX) с фактическим результатом и списком непроверенного руками.
