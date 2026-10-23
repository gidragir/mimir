# Mimir Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Собрать компилирующийся скелет Mimir: все модули из спеки, каждая точка входа запускается и показывает заглушку, CI собирает все артефакты.

**Architecture:** Gradle-монорепо на Kotlin Multiplatform. `core` (чистый Kotlin: порты, реестр облаков, декоратор шифрования, заглушки) → адаптер `drive` → общий Compose-интерфейс `ui` → тонкие запускалки (`app-desktop`, `app-android`, `web-ui`) + `daemon` (Ktor, HTTP API, раздаёт web UI) + Kotlin/JS-клиенты (`obsidian-plugin`, `browser-extension`). Общая конфигурация в convention-плагинах `build-logic`.

**Tech Stack:** Kotlin 2.4.21, JDK 27, Gradle ≥ 9.8 (Kotlin DSL, version catalog), Compose Multiplatform, AGP, Ktor (server CIO + content negotiation), kotlinx-coroutines, kotlinx-io, kotlinx-serialization, BuildConfig plugin (`com.github.gmazzo.buildconfig`), mise, GitHub Actions, Docker.

**Spec:** `docs/superpowers/specs/2026-10-23-mimir-design.md`

## Global Constraints

- Kotlin **2.4.21**, JDK **27**, Gradle **≥ 9.8**; остальные версии — последние стабильные на момент сборки. Если компонент не поддерживает JDK 27 (как JVM сборки или `jvmTarget`), откатить на **JDK 25** только этот компонент и записать причину в README (раздел «Версии»).
- Gradle: Kotlin DSL, `gradle/libs.versions.toml`, `org.gradle.configuration-cache=true`, `org.gradle.caching=true`.
- Все долгоживущие идентификаторы — только в `gradle.properties` (см. Task 1); в код и манифесты попадают через BuildConfig/генерацию/`processResources`, нигде не дублируются литералами.
- Пакет Kotlin: `dev.mimir.<module>` (корень берётся из `mimir.group`).
- `core` не зависит ни от чего платформенного; таргеты: `jvm`, `android` (если доступен SDK), `js(IR)` (browser), `wasmJs` (browser).
- Реальной синхронизации, шифрования и OAuth в скелете нет; заглушки бросают `NotImplementedError` с осмысленным сообщением.
- Локальный HTTP API: по умолчанию `127.0.0.1:47821`, адрес и порт настраиваются.
- Строка статуса до подключения: ровно `Mimir: not connected`.
- Linux: Arch, системная Java (`depends=('java-runtime>=27')`); Windows: MSI per-user, JRE вшита; Android: APK вне Play, `MANAGE_EXTERNAL_STORAGE`; браузер: только Chromium, Manifest V3; плагин Obsidian не `isDesktopOnly`.
- Секреты только в `mise.local.toml` (в `.gitignore`) и в GitHub Secrets.
- **Машина разработки — Arch Linux.** Все команды плана выполняются там (bash). На Windows-ПК пользователя ничего не устанавливать и не запускать: Windows-специфичное (MSI) проверяется только в CI. mise-задачи всё равно содержат `run_windows`, чтобы проект собирался и на Windows у других разработчиков.

## Review Focus

1. **Машина без Android SDK** (Arch-ноутбук, свежий Windows): `mise run build` должен пройти, пропустив Android-таргеты с предупреждением, а не упасть. → тест в Task 1 (Step 3).
2. **Нет `mise.local.toml`/секретов**: сборка и тесты проходят без единого секрета. → проверка в Task 1 (Step 5) и CI (Task 12).
3. **Трей недоступен** (niri без панели, Linux-заглушка, headless): приложение стартует и показывает окно, не падает. → тест в Task 5 (Step 1).
4. **Порт 47821 занят**: демон завершается с понятным сообщением `Port 47821 on 127.0.0.1 is already in use` и кодом 2, без стектрейса. → тест в Task 7 (Step 1).
5. **Плагин Obsidian на телефоне**: `main.js` не требует ничего, кроме модуля `obsidian` (никаких Node/Electron API). → проверка в Task 9 (Step 1).

Плюс внутри `core`: путь с вложенными папками шифруется **по сегментам** (`a/b/c.md` → три зашифрованных сегмента через `/`) — тест в Task 2.

---

### Task 1: Фундамент репозитория и build-logic

**Files:**
- Create: `.gitignore`, `.gitattributes`, `mise.toml`, `mise.local.toml.example`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Create: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/mimir.kmp-library.gradle.kts`, `build-logic/src/main/kotlin/dev/mimir/build/AndroidSdk.kt`
- Test: `build-logic/src/test/kotlin/dev/mimir/build/AndroidSdkTest.kt`

**Interfaces:**
- Produces: convention-плагин `mimir.kmp-library` (таргеты jvm, js browser, wasmJs browser, android-library — только при `AndroidSdk.isAvailable(rootDir)`); функция `AndroidSdk.isAvailable(rootDir: File, env: Map<String,String> = System.getenv()): Boolean`; свойства в `gradle.properties`:
  ```
  mimir.appName=Mimir
  mimir.version=0.1.0
  mimir.group=dev.mimir
  mimir.appId=dev.mimir.app
  mimir.obsidianPluginId=mimir-sync
  mimir.pacmanName=mimir
  mimir.dockerImage=mimir
  mimir.msiUpgradeUuid=8f3c2a1e-5b7d-4e9a-a6c0-2d1f4b8e7c35
  mimir.apiDefaultPort=47821
  ```
  В `settings.gradle.kts` `app-android` подключается только при `AndroidSdk.isAvailable`, иначе `logger.warn("Android SDK not found: skipping Android targets")`.

- [ ] **Step 1:** `git init`, ветка `main`; `.gitignore` (Gradle, IDE, `local.properties`, `mise.local.toml`, `*.keystore`, `build/`, `.kotlin/`), `.gitattributes` (`gradlew text eol=lf`, `*.bat text eol=crlf`).
- [ ] **Step 2:** `mise.toml`: `[tools] java = "temurin-27"` (если в реестре mise нет — `openjdk-27`), `node = "lts"`; задачи `build`, `test`, `desktop`, `android`, `daemon`, `web`, `plugin`, `extension`, `package:msi`, `package:arch`, `package:docker` — каждая с `run = "./gradlew …"` и `run_windows = "gradlew.bat …"`. `mise.local.toml.example`: `[env]` с `GOOGLE_OAUTH_CLIENT_ID = ""`, `GOOGLE_OAUTH_CLIENT_SECRET = ""`, `ANDROID_HOME = ""` (закомментированные пояснения на русском). Сгенерировать wrapper: `mise x gradle@9.8 -- gradle wrapper --gradle-version <последняя 9.x ≥ 9.8>`.
- [ ] **Step 3: Write the failing test** `AndroidSdkTest`:
  ```kotlin
  @Test fun `available when ANDROID_HOME points to existing dir`() =
      assertTrue(AndroidSdk.isAvailable(tmpRoot, mapOf("ANDROID_HOME" to existingDir.path)))
  @Test fun `available when local properties has sdk dir`() // local.properties: sdk.dir=<existingDir>
  @Test fun `not available when nothing configured`() =
      assertFalse(AndroidSdk.isAvailable(tmpRoot, emptyMap()))
  @Test fun `not available when ANDROID_HOME points to missing dir`()
  ```
- [ ] **Step 4:** Run `./gradlew -p build-logic test` → FAIL (нет `AndroidSdk`). Реализовать `AndroidSdk.isAvailable` (проверяет `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `sdk.dir` из `local.properties`; путь должен существовать), convention-плагин, корневые файлы. Run снова → PASS.
- [ ] **Step 5:** Без `mise.local.toml` и без Android SDK: `mise run build` → `BUILD SUCCESSFUL`, в выводе `Android SDK not found: skipping Android targets`.
- [ ] **Step 6: Commit** `chore: repository foundation, mise and build-logic`.

### Task 2: `core` — порты, реестр облаков, декоратор шифрования

**Files:**
- Create: `core/build.gradle.kts` (плагин `mimir.kmp-library`, BuildConfig в пакете `dev.mimir.core` с полями `APP_NAME`, `VERSION`, `APP_ID`, `OBSIDIAN_PLUGIN_ID`, `API_DEFAULT_PORT` из `gradle.properties`)
- Create в `core/src/commonMain/kotlin/dev/mimir/core/`: `api/RemoteStorage.kt`, `api/RemoteStorageProvider.kt`, `api/RemoteStorageRegistry.kt`, `api/AuthProvider.kt`, `api/LocalVault.kt`, `api/SecretStore.kt`, `api/NetworkState.kt`, `crypto/Cipher.kt`, `crypto/EncryptedRemoteStorage.kt`, `crypto/NotImplementedCipher.kt`, `sync/SyncEngine.kt`, `sync/StubSyncEngine.kt`, `sync/ConflictResolver.kt`, `testing/InMemoryRemoteStorage.kt`
- Test: `core/src/commonTest/kotlin/dev/mimir/core/` — `RemoteStorageRegistryTest.kt`, `InMemoryRemoteStorageTest.kt`, `EncryptedRemoteStorageTest.kt`, `StubSyncEngineTest.kt`

**Interfaces:**
- Produces (все в `dev.mimir.core.*`):
  ```kotlin
  @JvmInline value class RemotePath(val value: String)            // "/"-разделённый, без ведущего "/"
  data class RemoteEntry(val path: RemotePath, val size: Long, val modifiedAt: kotlin.time.Instant, val revision: String?, val isDirectory: Boolean)
  data class StorageCapabilities(val changeFeed: Boolean)
  interface RemoteStorage {
      suspend fun list(dir: RemotePath): List<RemoteEntry>
      suspend fun stat(path: RemotePath): RemoteEntry?
      suspend fun read(path: RemotePath): kotlinx.io.Source
      suspend fun write(path: RemotePath, data: kotlinx.io.Source): RemoteEntry
      suspend fun delete(path: RemotePath)
  }
  interface RemoteStorageProvider { val id: String; val displayName: String; val capabilities: StorageCapabilities; fun create(config: Map<String, String>): RemoteStorage }
  class RemoteStorageRegistry { fun register(provider: RemoteStorageProvider); operator fun get(id: String): RemoteStorageProvider?; fun all(): List<RemoteStorageProvider> }
  data class Account(val email: String, val displayName: String?)
  interface AuthProvider { suspend fun currentAccount(): Account?; suspend fun signIn(): Account; suspend fun signOut() }
  @JvmInline value class VaultFileId(val value: String)
  data class LocalEntry(val id: VaultFileId, val relativePath: String, val size: Long, val modifiedAt: kotlin.time.Instant)
  sealed interface VaultChange { val id: VaultFileId; data class Created(...); data class Modified(...); data class Deleted(...) }
  interface LocalVault { suspend fun list(): List<LocalEntry>; suspend fun read(id: VaultFileId): kotlinx.io.Source; suspend fun write(id: VaultFileId, data: kotlinx.io.Source); suspend fun delete(id: VaultFileId); fun changes(): kotlinx.coroutines.flow.Flow<VaultChange> }
  interface SecretStore { suspend fun get(key: String): String?; suspend fun put(key: String, value: String); suspend fun remove(key: String) }
  enum class NetworkKind { NONE, WIFI, METERED }
  interface NetworkState { val current: kotlinx.coroutines.flow.StateFlow<NetworkKind> }
  interface Cipher { fun encryptName(segment: String): String; fun decryptName(segment: String): String; fun encrypt(plain: kotlinx.io.Source): kotlinx.io.Source; fun decrypt(encrypted: kotlinx.io.Source): kotlinx.io.Source }
  class EncryptedRemoteStorage(private val inner: RemoteStorage, private val cipher: Cipher) : RemoteStorage
  object NotImplementedCipher : Cipher   // все методы: throw NotImplementedError("rclone crypt is not implemented yet")
  sealed interface SyncStatus { data object NotConfigured; data object Idle; data object Syncing; data class Error(val message: String) }
  interface SyncEngine { val status: kotlinx.coroutines.flow.StateFlow<SyncStatus>; suspend fun start(); suspend fun stop(); suspend fun syncNow() }
  class StubSyncEngine : SyncEngine   // status всегда NotConfigured; start/stop — no-op; syncNow — NotImplementedError
  data class Conflict(val id: VaultFileId, val local: ByteArray, val remote: ByteArray)
  sealed interface Resolution { data object KeepLocal; data object KeepRemote; data class Merged(val content: ByteArray) }
  interface ConflictResolver { suspend fun resolve(conflict: Conflict): Resolution }
  class InMemoryRemoteStorage : RemoteStorage   // для тестов и как образец адаптера
  data class TimeWindow(val start: kotlinx.datetime.LocalTime, val end: kotlinx.datetime.LocalTime)   // в core/rules/SyncRules.kt
  data class SyncRules(val wifiOnly: Boolean = false, val windows: List<TimeWindow> = emptyList(), val excludedGlobs: List<String> = listOf(".obsidian/workspace*.json"))   // только модель, без логики применения
  fun SyncStatus.label(): String     // NotConfigured -> "Mimir: not connected", Idle -> "Mimir: up to date", Syncing -> "Mimir: syncing", Error -> "Mimir: error — $message"
  ```
  Часы — `kotlin.time.Clock`, отдельный порт `Clock` не вводится (отступление от 6.1 спеки, т. к. в стандартной библиотеке он уже есть).

- [ ] **Step 1: Write the failing tests:**
  ```kotlin
  // RemoteStorageRegistryTest
  @Test fun `registered provider is found by id`()                 // register(fake "gdrive") -> registry["gdrive"] === fake
  @Test fun `unknown id returns null`()
  @Test fun `duplicate id is rejected`() = assertFailsWith<IllegalArgumentException> { registry.register(fakeA); registry.register(fakeA2SameId) }
  // InMemoryRemoteStorageTest
  @Test fun `write then read returns same bytes`()
  @Test fun `list returns direct children only`()                   // a/x.md, a/b/y.md -> list(a) = [a/x.md, a/b(dir)]
  @Test fun `delete removes entry and stat returns null`()
  // EncryptedRemoteStorageTest — с тестовым ReversingCipher (имя: reversed(), байты: xor 0x5A)
  @Test fun `path is encrypted per segment`()                       // write("Здоровье/Анализы.md") -> inner содержит "еьворовдЗ/дм.ызилианА"
  @Test fun `content stored in inner is encrypted and read back plain`()
  @Test fun `list decrypts names`()
  // StubSyncEngineTest
  @Test fun `initial status is NotConfigured with label Mimir not connected`() = assertEquals("Mimir: not connected", engine.status.value.label())
  ```
- [ ] **Step 2:** Run `./gradlew :core:allTests` → FAIL (компиляция).
- [ ] **Step 3:** Реализовать интерфейсы и классы выше. `EncryptedRemoteStorage` шифрует/дешифрует каждый сегмент `RemotePath` через `cipher.encryptName/decryptName` и содержимое через `encrypt/decrypt`; больше логики нет.
- [ ] **Step 4:** Run `./gradlew :core:allTests` → PASS на jvm, js, wasmJs (и android unit, если SDK есть).
- [ ] **Step 5: Commit** `feat(core): ports, storage registry, encryption decorator, stubs`.

### Task 3: `drive` — адаптер Google Drive (заглушка)

**Files:**
- Create: `drive/build.gradle.kts` (`mimir.kmp-library`, зависит от `:core`), `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorageProvider.kt`, `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleDriveStorage.kt`, `drive/src/commonMain/kotlin/dev/mimir/drive/GoogleAuthConfig.kt`
- Test: `drive/src/commonTest/kotlin/dev/mimir/drive/GoogleDriveStorageProviderTest.kt`

**Interfaces:**
- Consumes: `RemoteStorageProvider`, `RemoteStorage`, `StorageCapabilities` (Task 2).
- Produces: `object GoogleDriveStorageProvider : RemoteStorageProvider` с `id = "gdrive"`, `displayName = "Google Drive"`, `capabilities = StorageCapabilities(changeFeed = true)`; `object GoogleAuthConfig { val scopes: List<String> = listOf("https://www.googleapis.com/auth/drive.file", "openid", "email", "profile") }`; `GoogleDriveStorage` — все методы `NotImplementedError("Google Drive adapter is not implemented yet")`.

- [ ] **Step 1: Write the failing test:** `provider id is gdrive`, `scopes are exactly drive.file openid email profile` (сравнение со списком из спеки 6.4), `create returns storage whose read throws NotImplementedError`.
- [ ] **Step 2:** Run `./gradlew :drive:allTests` → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run `./gradlew :drive:allTests` → PASS.
- [ ] **Step 5: Commit** `feat(drive): Google Drive provider stub`.

### Task 4: `ui` — единый Compose-интерфейс

**Files:**
- Create: `ui/build.gradle.kts` (Compose Multiplatform; таргеты `jvm("desktop")`, `wasmJs` browser, android-library если SDK есть; зависит от `:core`, `:drive`)
- Create: `ui/src/commonMain/kotlin/dev/mimir/ui/MimirApp.kt`, `ui/src/commonMain/kotlin/dev/mimir/ui/screens/StatusScreen.kt`, `ui/src/commonMain/kotlin/dev/mimir/ui/MimirTheme.kt`
- Test: `ui/src/desktopTest/kotlin/dev/mimir/ui/StatusScreenTest.kt`

**Interfaces:**
- Consumes: `SyncEngine`, `SyncStatus.label()` (Task 2).
- Produces: `@Composable fun MimirApp(engine: SyncEngine)` — тема + `StatusScreen`; `StatusScreen` показывает заголовок `Mimir`, `engine.status` через `label()` и неактивную кнопку `Войти через Google`.

- [ ] **Step 1: Write the failing test** (`runComposeUiTest`): `shows app title`, `shows not connected status for stub engine` (`onNodeWithText("Mimir: not connected").assertExists()`), `sign in button is present and disabled` (`onNodeWithText("Войти через Google").assertIsNotEnabled()`).
- [ ] **Step 2:** Run `./gradlew :ui:desktopTest` → FAIL.
- [ ] **Step 3:** Реализовать `MimirApp`, `StatusScreen`, `MimirTheme` (Material 3, светлая/тёмная по системе).
- [ ] **Step 4:** Run `./gradlew :ui:desktopTest` → PASS; `./gradlew :ui:compileKotlinWasmJs` → успех.
- [ ] **Step 5: Commit** `feat(ui): shared status screen`.

### Task 5: `app-desktop` — запускалка, трей, упаковка

**Files:**
- Create: `app-desktop/build.gradle.kts`, `app-desktop/src/main/kotlin/dev/mimir/desktop/Main.kt`, `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/Tray.kt`, `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/AwtTray.kt`, `app-desktop/src/main/kotlin/dev/mimir/desktop/tray/NoTray.kt`, `app-desktop/src/main/resources/icon.png` (простая заглушка 256×256)
- Test: `app-desktop/src/test/kotlin/dev/mimir/desktop/tray/TrayTest.kt`

**Interfaces:**
- Consumes: `MimirApp`, `StubSyncEngine`.
- Produces:
  ```kotlin
  interface Tray { fun show(status: String, onOpen: () -> Unit, onQuit: () -> Unit); fun update(status: String); fun close() }
  fun createTray(osName: String = System.getProperty("os.name"), awtTraySupported: () -> Boolean = { java.awt.SystemTray.isSupported() }): Tray
  // Windows + awtTraySupported -> AwtTray; иначе (Linux/niri в скелете, headless, неподдержка) -> NoTray, который пишет в лог
  // "Tray is not available on this system; StatusNotifierItem support is planned" и больше ничего не делает.
  ```
  Закрытие окна сворачивает в трей, если трей есть; если `NoTray` — закрытие окна завершает приложение.
- `nativeDistributions`: `TargetFormat.Msi` с `perUserInstall = true`, `upgradeUuid` из `mimir.msiUpgradeUuid`, `packageName` из `mimir.appName`, `packageVersion` из `mimir.version`. Для Arch используется `packageUberJarForCurrentOS` (Java системная).

- [ ] **Step 1: Write the failing test** `TrayTest`: `windows with tray support gets AwtTray`, `linux gets NoTray`, `windows without tray support gets NoTray`, `NoTray show does not throw` (Review Focus 3).
- [ ] **Step 2:** Run `./gradlew :app-desktop:test` → FAIL.
- [ ] **Step 3:** Реализовать `Tray`, `createTray`, `Main.kt` (`application { Window(title = APP_NAME) { MimirApp(StubSyncEngine()) } }` + трей).
- [ ] **Step 4:** Run `./gradlew :app-desktop:test` → PASS; `./gradlew :app-desktop:packageUberJarForCurrentOS` → jar создан; ручная проверка на Arch/niri (с `xwayland-satellite`) `mise run desktop`: окно с `Mimir: not connected`, в логе сообщение `NoTray`.
- [ ] **Step 5:** MSI локально не собирается (машина разработки — Arch): задача `packageMsi` проверяется job'ом `msi` в CI (Task 12). Локально только `./gradlew :app-desktop:tasks --all | grep packageMsi` → задача существует.
- [ ] **Step 6: Commit** `feat(desktop): launcher, tray abstraction, MSI and uber-jar packaging`.

### Task 6: `app-android` — запускалка

**Files:**
- Create: `app-android/build.gradle.kts` (`com.android.application`, `applicationId` из `mimir.appId`, `minSdk = 26`, `compileSdk`/`targetSdk` — последние стабильные), `app-android/src/main/AndroidManifest.xml`, `app-android/src/main/kotlin/dev/mimir/android/MainActivity.kt`, `app-android/src/main/res/` (иконка-заглушка, `strings.xml` с `app_name` из `mimir.appName` через `resValue`)
- Test: `app-android/src/test/kotlin/dev/mimir/android/ManifestPermissionsTest.kt`

**Interfaces:**
- Consumes: `MimirApp`, `StubSyncEngine`.
- Produces: `MainActivity : ComponentActivity` с `setContent { MimirApp(StubSyncEngine()) }`. Разрешения в манифесте: `INTERNET`, `ACCESS_NETWORK_STATE`, `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. Подпись release: из env `MIMIR_KEYSTORE_PATH`, `MIMIR_KEYSTORE_PASSWORD`, `MIMIR_KEY_ALIAS`, `MIMIR_KEY_PASSWORD`; если их нет — release собирается неподписанным, без ошибки.

- [ ] **Step 1: Write the failing test:** парсит `src/main/AndroidManifest.xml` и проверяет, что все шесть разрешений присутствуют.
- [ ] **Step 2:** Run `./gradlew :app-android:testDebugUnitTest` → FAIL (только при наличии SDK; без SDK задача выполняется в CI).
- [ ] **Step 3:** Реализовать модуль.
- [ ] **Step 4:** Run `./gradlew :app-android:testDebugUnitTest :app-android:assembleDebug` → PASS, APK в `app-android/build/outputs/apk/debug/`.
- [ ] **Step 5: Commit** `feat(android): launcher activity and manifest`.

### Task 7: `daemon` — headless-движок и HTTP API

**Files:**
- Create: `daemon/build.gradle.kts` (JVM, `application`, Ktor server CIO, content negotiation + kotlinx-serialization), `daemon/src/main/kotlin/dev/mimir/daemon/Main.kt`, `daemon/src/main/kotlin/dev/mimir/daemon/DaemonConfig.kt`, `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt`
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/DaemonConfigTest.kt`, `daemon/src/test/kotlin/dev/mimir/daemon/RoutesTest.kt`, `daemon/src/test/kotlin/dev/mimir/daemon/PortInUseTest.kt`

**Interfaces:**
- Consumes: `StubSyncEngine`, `SyncStatus.label()`, BuildConfig `VERSION`, `API_DEFAULT_PORT`.
- Produces:
  ```kotlin
  data class DaemonConfig(val bind: String, val port: Int, val vaultPath: String?, val token: String?)
  fun DaemonConfig.Companion.fromEnv(env: Map<String, String>): DaemonConfig   // MIMIR_BIND (def "127.0.0.1"), MIMIR_PORT (def 47821), MIMIR_VAULT, MIMIR_TOKEN
  fun Application.mimirRoutes(engine: SyncEngine)
  // GET /health -> 200 {"status":"ok","version":"0.1.0"}
  // GET /api/status -> 200 {"status":"Mimir: not connected"}
  fun runDaemon(config: DaemonConfig): Int    // 0 — нормальное завершение, 2 — порт занят
  ```
  При занятом порте `runDaemon` печатает в stderr ровно `Port <port> on <bind> is already in use` и возвращает 2; `main` вызывает `exitProcess(runDaemon(...))`. Проверка токена — заглушка (поле есть, не проверяется), отмечено TODO-комментарием со ссылкой на спеку 6.2.

- [ ] **Step 1: Write the failing tests:** `defaults are 127.0.0.1 and 47821`, `env overrides bind and port`, `invalid MIMIR_PORT fails with IllegalArgumentException mentioning MIMIR_PORT`; `health returns ok and version` (`testApplication`); `status returns not connected label`; `PortInUseTest`: занять порт `ServerSocket(0)`, вызвать `runDaemon` с этим портом → результат `2`, stderr содержит `is already in use` (Review Focus 4).
- [ ] **Step 2:** Run `./gradlew :daemon:test` → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run `./gradlew :daemon:test` → PASS; `mise run daemon` + `curl -s 127.0.0.1:47821/health` → `{"status":"ok","version":"0.1.0"}`.
- [ ] **Step 5: Commit** `feat(daemon): headless entry point with health and status API`.

### Task 8: `web-ui` — web-интерфейс, который раздаёт демон

**Files:**
- Create: `web-ui/build.gradle.kts` (Kotlin MP `wasmJs { browser(); binaries.executable() }`, Compose, зависит от `:ui`, `:core`), `web-ui/src/wasmJsMain/kotlin/dev/mimir/web/Main.kt`, `web-ui/src/wasmJsMain/resources/index.html`
- Modify: `daemon/build.gradle.kts` (ресурсы `static/` ← результат `:web-ui:wasmJsBrowserDistribution`), `daemon/src/main/kotlin/dev/mimir/daemon/Routes.kt` (`staticResources("/", "static")`)
- Test: `daemon/src/test/kotlin/dev/mimir/daemon/WebUiServedTest.kt`

**Interfaces:**
- Consumes: `MimirApp`, `StubSyncEngine`, `mimirRoutes`.
- Produces: `index.html` с `<title>Mimir</title>`, который грузит wasm-бандл и вызывает `ComposeViewport { MimirApp(StubSyncEngine()) }`. В скелете web UI показывает статус заглушки из `core`, не опрашивая `/api/status` (опрос — следующий этап).

- [ ] **Step 1: Write the failing test:** `GET / returns index html with Mimir title` (200, `Content-Type` text/html, тело содержит `<title>Mimir</title>`).
- [ ] **Step 2:** Run `./gradlew :daemon:test --tests '*WebUiServedTest*'` → FAIL.
- [ ] **Step 3:** Реализовать модуль и подключение к демону.
- [ ] **Step 4:** Run `./gradlew :daemon:test` → PASS; `mise run daemon`, открыть `http://127.0.0.1:47821/` в браузере → экран `Mimir: not connected`.
- [ ] **Step 5: Commit** `feat(web-ui): wasm web UI served by daemon`.

### Task 9: `obsidian-plugin` — плагин Obsidian

**Files:**
- Create: `obsidian-plugin/build.gradle.kts` (Kotlin/JS IR, `browser { webpackTask { output.libraryTarget = "commonjs2" } }`, `binaries.executable()`), `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/ObsidianApi.kt` (external-декларации `@JsModule("obsidian")`: `Plugin`, `App`, `onload`, `addStatusBarItem(): HTMLElement`), `obsidian-plugin/src/jsMain/kotlin/dev/mimir/obsidian/MimirPlugin.kt`, `obsidian-plugin/webpack.config.d/obsidian.js` (`config.externals = { obsidian: "commonjs obsidian" }`), `obsidian-plugin/manifest.template.json`
- Create: `obsidian-plugin/scripts/check-plugin.mjs`
- Gradle-задачи: `:obsidian-plugin:assemblePlugin` (кладёт `main.js` + `manifest.json` в `obsidian-plugin/build/plugin/`), `:obsidian-plugin:zipPlugin`, `:obsidian-plugin:checkPlugin` (запускает `node scripts/check-plugin.mjs build/plugin`; входит в `check`)

**Interfaces:**
- Consumes: `StubSyncEngine`, `SyncStatus.label()` (Kotlin/JS-таргет `core`), BuildConfig `OBSIDIAN_PLUGIN_ID`, `APP_NAME`, `VERSION`.
- Produces: `manifest.json` = `{"id": "<mimir.obsidianPluginId>", "name": "<mimir.appName>", "version": "<mimir.version>", "minAppVersion": "1.5.0", "description": "Encrypted Google Drive sync status for Mimir", "isDesktopOnly": false}`; `main.js`, у которого `module.exports.default` — класс плагина; в `onload()` добавляет элемент строки состояния с текстом `Mimir: not connected`.

- [ ] **Step 1: Write the failing check** `check-plugin.mjs`: подменяет `require('obsidian')` фейком (`class Plugin { addStatusBarItem() { return el } }`), загружает `main.js`, создаёт `new (module.exports.default)()`, вызывает `onload()` и проверяет: (а) `el.textContent === "Mimir: not connected"`; (б) `manifest.json.isDesktopOnly === false`; (в) единственный `require(...)` в `main.js` — `obsidian` (перехватчик `require` бросает на любой другой модуль; Review Focus 5). Выход `0`/`1`.
- [ ] **Step 2:** Run `./gradlew :obsidian-plugin:checkPlugin` → FAIL.
- [ ] **Step 3:** Реализовать плагин и задачи сборки. Если Kotlin/JS не даёт `module.exports.default` напрямую, добавить в `assemblePlugin` однострочную обёртку `module.exports = { default: require('./mimir-plugin.js').MimirPlugin }` и бандлить её — без ручной правки сгенерированного кода.
- [ ] **Step 4:** Run `./gradlew :obsidian-plugin:checkPlugin` → PASS. Ручная проверка: скопировать `build/plugin/` в `<vault>/.obsidian/plugins/mimir-sync/`, включить плагин → в строке состояния `Mimir: not connected`.
- [ ] **Step 5: Commit** `feat(obsidian-plugin): status bar stub`.

### Task 10: `browser-extension` — расширение для Chromium

**Files:**
- Create: `browser-extension/build.gradle.kts` (Kotlin/JS IR, browser, executable), `browser-extension/src/jsMain/kotlin/dev/mimir/extension/Popup.kt`, `browser-extension/src/jsMain/resources/popup.html`, `browser-extension/manifest.template.json`, `browser-extension/src/jsMain/resources/icon-128.png`, `browser-extension/scripts/check-extension.mjs`
- Gradle-задачи: `:browser-extension:assembleExtension` (→ `browser-extension/build/extension/`), `:browser-extension:zipExtension`, `:browser-extension:checkExtension` (входит в `check`)

**Interfaces:**
- Consumes: Kotlin/JS-таргет `core` (BuildConfig `APP_NAME`, `VERSION`; `NotImplementedCipher` — чтобы доказать, что код шифрования из `core` собирается в расширение).
- Produces: `manifest.json` = MV3: `"manifest_version": 3`, `"name": "<mimir.appName> Quick View"`, `"version": "<mimir.version>"`, `"action": {"default_popup": "popup.html"}`, `"permissions": ["identity"]`, `"host_permissions": ["https://drive.google.com/*", "https://www.googleapis.com/*"]`. Popup показывает `Mimir Quick View` и `Расшифровка ещё не реализована`.

- [ ] **Step 1: Write the failing check** `check-extension.mjs`: в каталоге есть `manifest.json`, `popup.html`, бандл JS, на который ссылается `popup.html`; `manifest_version === 3`; `action.default_popup === "popup.html"`; ключа `background.scripts` нет (MV3 Chromium допускает только `service_worker`).
- [ ] **Step 2:** Run `./gradlew :browser-extension:checkExtension` → FAIL.
- [ ] **Step 3:** Реализовать.
- [ ] **Step 4:** Run `./gradlew :browser-extension:checkExtension` → PASS. Ручная проверка: `chrome://extensions` → «Загрузить распакованное» → popup показывает текст.
- [ ] **Step 5: Commit** `feat(browser-extension): MV3 popup stub`.

### Task 11: Упаковка — Arch PKGBUILD и Docker

**Files:**
- Create: `.github/scripts/build-arch.sh` (копирует uber-jar в `packaging/arch/mimir.jar`, подставляет `pkgver`, запускает `makepkg -f`), `packaging/arch/PKGBUILD`, `packaging/arch/mimir.sh` (лаунчер: `exec java -jar /usr/share/java/mimir/mimir.jar "$@"`), `packaging/arch/mimir.desktop`, `packaging/docker/Dockerfile`, `.dockerignore`

**Interfaces:**
- Consumes: uber-jar `app-desktop` (Task 5), `:daemon:installDist` с web UI (Tasks 7–8).
- Produces:
  - PKGBUILD: `pkgname` из `mimir.pacmanName`, `pkgver` из `mimir.version` (CI подставляет `sed`'ом), `arch=('x86_64')`, `depends=('java-runtime>=27')`, `source` — локальные файлы (`mimir.jar`, `mimir.sh`, `mimir.desktop`, иконка), кладёт jar в `/usr/share/java/mimir/mimir.jar`, лаунчер в `/usr/bin/mimir`, `.desktop` в `/usr/share/applications/`. Собирается `makepkg` в контейнере `archlinux:latest` в CI.
  - Dockerfile: multi-stage; stage `build` на образе с JDK 27 выполняет `./gradlew :daemon:installDist`; runtime на JRE 27 (`eclipse-temurin:27-jre` или ближайший доступный), непривилегированный пользователь `mimir`, `ENV MIMIR_BIND=0.0.0.0 MIMIR_PORT=47821 MIMIR_VAULT=/vault`, `VOLUME /vault`, `EXPOSE 47821`, `HEALTHCHECK` через `/health`.

- [ ] **Step 1:** Написать Dockerfile; Run `docker build -f packaging/docker/Dockerfile -t mimir:dev .` → успех.
- [ ] **Step 2:** Run `docker run -d --rm -p 127.0.0.1:47821:47821 --name mimir-dev mimir:dev && curl -s 127.0.0.1:47821/health` → `{"status":"ok","version":"0.1.0"}`; `docker stop mimir-dev`.
- [ ] **Step 3:** Написать PKGBUILD и сопутствующие файлы; проверить прямо на машине разработки (Arch): `./gradlew :app-desktop:packageUberJarForCurrentOS && cd packaging/arch && makepkg -f` (скрипт копирования jar — тот же, что в `.github/scripts/build-arch.sh` из Task 12; написать его здесь и переиспользовать в CI) → в `packaging/arch/` появился `mimir-0.1.0-1-x86_64.pkg.tar.zst`; `namcap` на PKGBUILD без ошибок (предупреждения допустимы).
- [ ] **Step 4: Commit** `build: Arch package and Docker image`.

### Task 12: GitHub Actions

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml` (скрипт `.github/scripts/build-arch.sh` уже создан в Task 11)

**Interfaces:**
- Consumes: Gradle-задачи из Tasks 1–11; секреты `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` (пока не используются, пробрасываются в env), `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Produces:
  - `ci.yml` (push, pull_request): `ubuntu-latest`, `jdx/mise-action` (ставит JDK/Node из `mise.toml`), `android-actions/setup-android`, Gradle cache (`gradle/actions/setup-gradle`), `./gradlew build` (включает тесты, `checkPlugin`, `checkExtension`, `assembleDebug`). Без каких-либо секретов (Review Focus 2).
  - `release.yml` (теги `v*`): jobs `msi` (`windows-latest`, `packageMsi`), `arch` (`ubuntu-latest` с `container: archlinux:latest`, `build-arch.sh`: pacman -Syu jdk-openjdk base-devel → `packageUberJarForCurrentOS` → `makepkg`), `apk` (`assembleRelease`, keystore из base64-секрета; без секрета — неподписанный APK с суффиксом `-unsigned`), `docker` (build + push в `ghcr.io/${{ github.repository_owner }}/<mimir.dockerImage>:<tag>` и `:latest`, `permissions: packages: write`), `zips` (`zipPlugin`, `zipExtension`), `publish` (зависит от всех; `softprops/action-gh-release` прикладывает MSI, `.pkg.tar.zst`, APK, оба zip).

- [ ] **Step 1:** Написать workflows и скрипт.
- [ ] **Step 2:** Run `mise x actionlint -- actionlint` → без ошибок.
- [ ] **Step 3: Commit** `ci: build workflow and release artifacts`.
- [ ] **Step 4:** (после создания приватного репозитория пользователем и push) убедиться, что `ci.yml` зелёный; тестовый тег `v0.1.0-rc1` → в релизе пять артефактов. Создание репозитория и push — только с явного согласия пользователя.

### Task 13: README и финальная проверка

**Files:**
- Create: `README.md`

- [ ] **Step 1:** README (на русском): что такое Mimir (2–3 абзаца из раздела 1 спеки), структура модулей (таблица из 6.1), «Как начать»: `mise install`, `cp mise.local.toml.example mise.local.toml`, `mise run build`, команды запуска каждой точки входа; Android SDK (необязателен, как указать `ANDROID_HOME`); niri: окно требует `xwayland-satellite`, трей пока не реализован (StatusNotifierItem — план); Google Cloud: один проект для всех клиентов, rclone как запасной выход с client ID того же проекта (спека 6.4); раздел «Версии» (включая откаты на JDK 25, если были).
- [ ] **Step 2:** Финальная проверка с чистого клона без `mise.local.toml`: `mise install && mise run build` → `BUILD SUCCESSFUL`; `mise run test` → все тесты зелёные.
- [ ] **Step 3: Commit** `docs: README with getting started`.
